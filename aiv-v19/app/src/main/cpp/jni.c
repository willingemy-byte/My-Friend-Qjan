#include <jni.h>
#include "relay.h"
typedef struct {JNIEnv *env;jobject object;jmethodID stop,protect,open,update,direction,dns,tls,problem;int failed;} java_listener;
static int check(java_listener *j){if((*j->env)->ExceptionCheck(j->env)){(*j->env)->ExceptionClear(j->env);j->failed=1;}return j->failed;}
static int stop(void *data){java_listener *j=data;if(j->failed)return 1;int result=(*j->env)->CallBooleanMethod(j->env,j->object,j->stop);return check(j)||result;}
static int protect_socket(void *data,int fd){java_listener *j=data;int result=(*j->env)->CallBooleanMethod(j->env,j->object,j->protect,(jint)fd);return !check(j)&&result;}
static void open_flow(void *data,uint64_t id,const zdtun_5tuple_t *tuple){
    java_listener *j=data;char src[INET6_ADDRSTRLEN],dst[INET6_ADDRSTRLEN];zdtun_ip_t local,remote;memcpy(&local,&tuple->src_ip,sizeof(local));memcpy(&remote,&tuple->dst_ip,sizeof(remote));
    int af=tuple->ipver==4?AF_INET:AF_INET6;
    if(!inet_ntop(af,&local,src,sizeof(src))||!inet_ntop(af,&remote,dst,sizeof(dst))){j->failed=1;return;}
    jstring a=(*j->env)->NewStringUTF(j->env,src),b=(*j->env)->NewStringUTF(j->env,dst);
    if(!check(j))(*j->env)->CallVoidMethod(j->env,j->object,j->open,(jlong)id,(jint)tuple->ipver,(jint)(tuple->ipver==6&&tuple->ipproto==1?58:tuple->ipproto),a,(jint)ntohs(tuple->src_port),b,(jint)ntohs(tuple->dst_port));
    if(a)(*j->env)->DeleteLocalRef(j->env,a);if(b)(*j->env)->DeleteLocalRef(j->env,b);check(j);
}
static void update(void *data,uint64_t id,uint64_t tx,uint64_t rx,uint64_t txp,uint64_t rxp,int status,int error,int closed,uint64_t ms){
    java_listener *j=data;if(j->failed)return;
    (*j->env)->CallVoidMethod(j->env,j->object,j->update,(jlong)id,(jlong)tx,(jlong)rx,(jlong)txp,(jlong)rxp,(jint)status,(jint)error,(jboolean)closed,(jlong)ms);check(j);
}
static void direction(void *data,uint64_t id,int outgoing,uint64_t bytes,uint64_t ms){
    java_listener *j=data;if(j->failed)return;
    (*j->env)->CallVoidMethod(j->env,j->object,j->direction,(jlong)id,(jboolean)outgoing,(jlong)bytes,(jlong)ms);check(j);
}
static void dns(void *data,uint64_t id,const char *name,int type){
    java_listener *j=data;if(j->failed)return;jstring s=(*j->env)->NewStringUTF(j->env,name);
    if(!check(j))(*j->env)->CallVoidMethod(j->env,j->object,j->dns,(jlong)id,s,(jint)type);
    if(s)(*j->env)->DeleteLocalRef(j->env,s);check(j);
}
static void tls(void *data,uint64_t id,const char *name,int status,int ech){
    java_listener *j=data;if(j->failed)return;jstring s=(*j->env)->NewStringUTF(j->env,name);
    if(!check(j))(*j->env)->CallVoidMethod(j->env,j->object,j->tls,(jlong)id,s,(jint)status,(jboolean)ech);
    if(s)(*j->env)->DeleteLocalRef(j->env,s);check(j);
}
static void problem(void *data,const char *label,uint64_t count){
    java_listener *j=data;if(j->failed)return;jstring s=(*j->env)->NewStringUTF(j->env,label);
    if(!check(j))(*j->env)->CallVoidMethod(j->env,j->object,j->problem,s,(jlong)count);
    if(s)(*j->env)->DeleteLocalRef(j->env,s);check(j);
}
JNIEXPORT jint JNICALL Java_fr_erick_journallocal_NetworkCaptureService_runNative(JNIEnv *env,jobject object,jint fd){
    java_listener j={.env=env,.object=object};jclass cls=(*env)->GetObjectClass(env,object);if(!cls)return -50;
    j.stop=(*env)->GetMethodID(env,cls,"shouldStopNative","()Z");
    j.protect=(*env)->GetMethodID(env,cls,"protectNativeSocket","(I)Z");
    j.open=(*env)->GetMethodID(env,cls,"onFlowOpen","(JIILjava/lang/String;ILjava/lang/String;I)V");
    j.update=(*env)->GetMethodID(env,cls,"onFlowUpdate","(JJJJJIIZJ)V");
    j.direction=(*env)->GetMethodID(env,cls,"onFlowDirection","(JZJJ)V");
    j.dns=(*env)->GetMethodID(env,cls,"onDnsQuestion","(JLjava/lang/String;I)V");
    j.tls=(*env)->GetMethodID(env,cls,"onTlsHello","(JLjava/lang/String;IZ)V");
    j.problem=(*env)->GetMethodID(env,cls,"onNativeProblem","(Ljava/lang/String;J)V");
    (*env)->DeleteLocalRef(env,cls);
    if(check(&j)||!j.stop||!j.protect||!j.open||!j.update||!j.direction||!j.dns||!j.tls||!j.problem)return -50;
    journal_listener listener={.data=&j,.should_stop=stop,.protect_socket=protect_socket,.opened=open_flow,.updated=update,.direction=direction,.dns_question=dns,.tls_hello=tls,.problem=problem};
    int result=journal_relay(fd,&listener);return j.failed?-50:result;
}