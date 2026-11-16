// Observe only the pinned engine's imported networking calls. Every argument,
// return value and errno reaches the original caller unchanged; no payloads are
// inspected or retained. The host/backend's own imports are not intercepted.
#include "network_startup_diagnostics.h"
#include <arpa/inet.h>
#include <atomic>
#include <cerrno>
#include <cstdio>
#include <dlfcn.h>
#include <netdb.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <unistd.h>

namespace {
struct Calls {
    std::atomic<unsigned> total{0}, failures{0};
    std::atomic<int> error{0};
    void record(bool failed, int code) {
        total.fetch_add(1, std::memory_order_relaxed);
        if (failed) {
            failures.fetch_add(1, std::memory_order_relaxed);
            error.store(code, std::memory_order_relaxed);
        }
    }
};
Calls sockets, binds, connects, sends, names, dns;
std::atomic<unsigned> installed{0};
std::atomic<bool> attempted{false};
std::atomic<unsigned> ipv4_attempts{0}, loopback_attempts{0}, loopback_successes{0};
std::atomic<uint32_t> last_ip{0}, loopback_ip{0};
std::atomic<unsigned> last_port{0}, loopback_port{0};
using Socket = int (*)(int,int,int);
using Address = int (*)(int,const sockaddr *,socklen_t);
using SendTo = ssize_t (*)(int,const void *,size_t,int,const sockaddr *,socklen_t);
using SendMsg = ssize_t (*)(int,const msghdr *,int);
using SockName = int (*)(int,sockaddr *,socklen_t *);
using Dns = int (*)(const char *,const char *,const addrinfo *,addrinfo **);
std::atomic<Socket> original_socket{nullptr};
std::atomic<Address> original_bind{nullptr}, original_connect{nullptr};
std::atomic<SendTo> original_sendto{nullptr};
std::atomic<SendMsg> original_sendmsg{nullptr};
std::atomic<SockName> original_name{nullptr};
std::atomic<Dns> original_dns{nullptr};

void destination(const sockaddr *address, socklen_t size, ssize_t result, int error) {
    // An EFAULT may mean the pointer itself was invalid. Leave its validation
    // to the original kernel call; diagnostics must not turn an error into a crash.
    if (!address || error == EFAULT || size < sizeof(sockaddr_in) || address->sa_family != AF_INET) return;
    const auto *v4 = reinterpret_cast<const sockaddr_in *>(address);
    const uint32_t ip = v4->sin_addr.s_addr;
    const unsigned port = ntohs(v4->sin_port);
    ipv4_attempts.fetch_add(1, std::memory_order_relaxed);
    last_ip.store(ip, std::memory_order_relaxed);
    last_port.store(port, std::memory_order_relaxed);
    if ((ntohl(ip) >> 24) == 127) {
        loopback_attempts.fetch_add(1, std::memory_order_relaxed);
        if (result >= 0) loopback_successes.fetch_add(1, std::memory_order_relaxed);
        loopback_ip.store(ip, std::memory_order_relaxed);
        loopback_port.store(port, std::memory_order_relaxed);
    }
}
int observe_socket(int family, int type, int protocol) {
    const int result = original_socket.load(std::memory_order_acquire)(family,type,protocol);
    const int error = errno;
    sockets.record(result < 0, error); errno = error; return result;
}
int observe_bind(int fd, const sockaddr *address, socklen_t size) {
    const int result = original_bind.load(std::memory_order_acquire)(fd,address,size);
    const int error = errno;
    binds.record(result < 0, error); errno = error; return result;
}
int observe_connect(int fd, const sockaddr *address, socklen_t size) {
    const int result = original_connect.load(std::memory_order_acquire)(fd,address,size);
    const int error = errno;
    connects.record(result < 0 && error != EINPROGRESS && error != EALREADY, error);
    errno = error; return result;
}
ssize_t observe_sendto(int fd, const void *data, size_t size, int flags,
                       const sockaddr *address, socklen_t address_size) {
    const ssize_t result = original_sendto.load(std::memory_order_acquire)(fd,data,size,flags,address,address_size);
    const int error = errno;
    sends.record(result < 0, error);
    destination(address,address_size,result,result < 0 ? error : 0);
    errno = error; return result;
}
ssize_t observe_sendmsg(int fd, const msghdr *message, int flags) {
    const ssize_t result = original_sendmsg.load(std::memory_order_acquire)(fd,message,flags);
    const int error = errno;
    sends.record(result < 0, error);
    if (message && (result >= 0 || error != EFAULT))
        destination(static_cast<const sockaddr *>(message->msg_name),message->msg_namelen,result,result < 0 ? error : 0);
    errno = error; return result;
}
int observe_name(int fd, sockaddr *address, socklen_t *size) {
    const int result = original_name.load(std::memory_order_acquire)(fd,address,size);
    const int error = errno;
    names.record(result < 0, error); errno = error; return result;
}
int observe_dns(const char *node, const char *service, const addrinfo *hints, addrinfo **output) {
    const int result = original_dns.load(std::memory_order_acquire)(node,service,hints,output);
    const int error = errno;
    dns.record(result != 0, result); errno = error; return result;
}
template<typename Fn>
bool install(uintptr_t base, uintptr_t offset, const char *symbol, Fn replacement, std::atomic<Fn> &original) {
    auto *slot = reinterpret_cast<uintptr_t *>(base + offset);
    const auto expected = reinterpret_cast<uintptr_t>(dlsym(RTLD_DEFAULT,symbol));
    if (!expected || __atomic_load_n(slot,__ATOMIC_ACQUIRE) != expected) return false;
    const long size = sysconf(_SC_PAGESIZE);
    if (size < 4096 || size > 65536 || (size & (size-1))) return false;
    auto *page = reinterpret_cast<void *>(reinterpret_cast<uintptr_t>(slot) & ~(uintptr_t(size)-1));
    if (mprotect(page,size_t(size),PROT_READ|PROT_WRITE)) return false;
    original.store(reinterpret_cast<Fn>(expected),std::memory_order_release);
    __atomic_store_n(slot,reinterpret_cast<uintptr_t>(replacement),__ATOMIC_RELEASE);
    if (!mprotect(page,size_t(size),PROT_READ)) return true;
    __atomic_store_n(slot,expected,__ATOMIC_RELEASE);
    mprotect(page,size_t(size),PROT_READ);
    return false;
}
}

void peerlink_observe_network_sockets(uintptr_t base) {
    if (!base || attempted.exchange(true)) return;
    unsigned bits = 0;
    if (install(base,0x99f71b8,"socket",observe_socket,original_socket)) bits |= 1;
    if (install(base,0x99f71d8,"bind",observe_bind,original_bind)) bits |= 2;
    if (install(base,0x99f71e0,"connect",observe_connect,original_connect)) bits |= 4;
    if (install(base,0x99f71f8,"sendto",observe_sendto,original_sendto)) bits |= 8;
    if (install(base,0x99f7208,"getsockname",observe_name,original_name)) bits |= 16;
    if (install(base,0x99f7240,"getaddrinfo",observe_dns,original_dns)) bits |= 32;
    if (install(base,0x99f7b90,"sendmsg",observe_sendmsg,original_sendmsg)) bits |= 64;
    installed.store(bits,std::memory_order_release);
}

void peerlink_network_socket_diagnostics(char *text, size_t size) {
    char target[INET_ADDRSTRLEN]="unavailable", local[INET_ADDRSTRLEN]="unavailable";
    const uint32_t ip=last_ip.load(), loop=loopback_ip.load();
    if (ipv4_attempts.load()) inet_ntop(AF_INET,&ip,target,sizeof(target));
    if (loopback_attempts.load()) inet_ntop(AF_INET,&loop,local,sizeof(local));
    snprintf(text,size,
        " socketObservers=%u socketCalls=%u socketFailures=%u socketErrno=%d"
        " bindCalls=%u bindFailures=%u bindErrno=%d connectCalls=%u connectFailures=%u connectErrno=%d"
        " nameCalls=%u nameFailures=%u nameErrno=%d dnsCalls=%u dnsFailures=%u dnsEai=%d"
        " sendCalls=%u sendFailures=%u sendErrno=%d ipv4SendAttempts=%u lastIpv4Send=%s:%u"
        " loopbackSendAttempts=%u loopbackSendAccepted=%u lastLoopbackSend=%s:%u",
        installed.load(),sockets.total.load(),sockets.failures.load(),sockets.error.load(),
        binds.total.load(),binds.failures.load(),binds.error.load(),connects.total.load(),connects.failures.load(),connects.error.load(),
        names.total.load(),names.failures.load(),names.error.load(),dns.total.load(),dns.failures.load(),dns.error.load(),
        sends.total.load(),sends.failures.load(),sends.error.load(),ipv4_attempts.load(),target,last_port.load(),
        loopback_attempts.load(),loopback_successes.load(),local,loopback_port.load());
}
