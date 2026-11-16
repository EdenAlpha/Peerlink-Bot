#include "network_startup_diagnostics.h"
#include <arpa/inet.h>
#include <cassert>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <netdb.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <unistd.h>

// Exercise the observer with real libc sockets and a synthetic pinned import
// table. Verify actual packet bytes/targets and preserved failures, not mocked
// success flags. The sparse mapping does not allocate the complete image size.
int main() {
    constexpr size_t image_size=0x9a00000;
    void *mapping=mmap(nullptr,image_size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(mapping!=MAP_FAILED);
    uintptr_t base=reinterpret_cast<uintptr_t>(mapping);
    struct Import { uintptr_t offset; const char *name; } imports[] = {
        {0x99f71b8,"socket"},{0x99f71d8,"bind"},{0x99f71e0,"connect"},
        {0x99f71f8,"sendto"},{0x99f7208,"getsockname"},{0x99f7240,"getaddrinfo"},
        {0x99f7b90,"sendmsg"}
    };
    for (const auto &entry:imports) {
        void *target=dlsym(RTLD_DEFAULT,entry.name); assert(target);
        *reinterpret_cast<void **>(base+entry.offset)=target;
    }
    peerlink_observe_network_sockets(base);
    auto observed_socket=*reinterpret_cast<decltype(&socket) *>(base+0x99f71b8);
    auto observed_bind=*reinterpret_cast<decltype(&bind) *>(base+0x99f71d8);
    auto observed_connect=*reinterpret_cast<decltype(&connect) *>(base+0x99f71e0);
    auto observed_sendto=*reinterpret_cast<decltype(&sendto) *>(base+0x99f71f8);
    auto observed_sendmsg=*reinterpret_cast<decltype(&sendmsg) *>(base+0x99f7b90);
    auto observed_name=*reinterpret_cast<decltype(&getsockname) *>(base+0x99f7208);
    auto observed_dns=*reinterpret_cast<decltype(&getaddrinfo) *>(base+0x99f7240);
    int receiver=socket(AF_INET,SOCK_DGRAM,0); assert(receiver>=0);
    sockaddr_in address{}; address.sin_family=AF_INET;
    assert(inet_pton(AF_INET,"127.0.0.1",&address.sin_addr)==1);
    assert(bind(receiver,reinterpret_cast<sockaddr *>(&address),sizeof(address))==0);
    socklen_t length=sizeof(address);
    assert(getsockname(receiver,reinterpret_cast<sockaddr *>(&address),&length)==0);
    int sender=observed_socket(AF_INET,SOCK_DGRAM,0); assert(sender>=0);
    unsigned char payload[]={0x00,0xff,0x42,0x01,0xab};
    errno=ENOTTY;
    assert(observed_sendto(sender,payload,sizeof(payload),0,reinterpret_cast<sockaddr *>(&address),sizeof(address))==sizeof(payload));
    assert(errno==ENOTTY);
    unsigned char received[32]{};
    assert(recv(receiver,received,sizeof(received),0)==sizeof(payload));
    assert(!memcmp(payload,received,sizeof(payload)));
    iovec buffer{payload,sizeof(payload)};
    msghdr message{}; message.msg_name=&address; message.msg_namelen=sizeof(address);
    message.msg_iov=&buffer; message.msg_iovlen=1;
    assert(observed_sendmsg(sender,&message,0)==sizeof(payload));
    assert(recv(receiver,received,sizeof(received),0)==sizeof(payload));
    assert(!memcmp(payload,received,sizeof(payload)));
    int conflict=observed_socket(AF_INET,SOCK_DGRAM,0); assert(conflict>=0);
    assert(observed_bind(conflict,reinterpret_cast<sockaddr *>(&address),sizeof(address))==-1);
    assert(errno==EADDRINUSE);
    close(conflict);
    assert(observed_connect(-1,reinterpret_cast<sockaddr *>(&address),sizeof(address))==-1);
    assert(errno==EBADF);
    assert(observed_sendto(-1,payload,sizeof(payload),0,reinterpret_cast<sockaddr *>(&address),sizeof(address))==-1);
    assert(errno==EBADF);
    assert(observed_name(-1,reinterpret_cast<sockaddr *>(&address),&length)==-1);
    assert(errno==EBADF);
    addrinfo hints{}; hints.ai_flags=AI_NUMERICHOST; hints.ai_family=AF_INET;
    addrinfo *output=nullptr;
    assert(observed_dns("not-a-numeric-host",nullptr,&hints,&output)==EAI_NONAME);
    char report[2048]; peerlink_network_socket_diagnostics(report,sizeof(report));
    assert(strstr(report,"socketObservers=127"));
    assert(strstr(report,"bindCalls=1 bindFailures=1"));
    assert(strstr(report,"sendCalls=3 sendFailures=1"));
    assert(strstr(report,"dnsCalls=1 dnsFailures=1"));
    assert(strstr(report,"loopbackSendAttempts=3 loopbackSendAccepted=2"));
    char target[80]; snprintf(target,sizeof(target),"lastLoopbackSend=127.0.0.1:%u",ntohs(address.sin_port));
    assert(strstr(report,target));
    close(sender);close(receiver);munmap(mapping,image_size);
    puts("SOCKET_OBSERVER_REAL_DATAGRAM_AND_ERROR_PRESERVATION_PASS");
}
