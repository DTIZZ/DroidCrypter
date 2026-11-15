// Secure memory wipe for Java byte arrays.
//
// The array is pinned with GetPrimitiveArrayCritical so we zero the real heap storage (not a copy),
// written through a volatile pointer so the stores can't be elided, followed by a compiler barrier.
#include <jni.h>
#include <cstddef>

namespace {

void secure_zero(void* p, size_t n) {
    volatile unsigned char* v = static_cast<volatile unsigned char*>(p);
    while (n--) *v++ = 0;
    __asm__ __volatile__("" : : "r"(p) : "memory");
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_app_droidcrypter_security_NativeWipe_wipe(JNIEnv* env, jclass, jbyteArray array) {
    if (array == nullptr) return;
    const jsize len = env->GetArrayLength(array);
    if (len <= 0) return;
    jboolean isCopy = JNI_FALSE;
    void* data = env->GetPrimitiveArrayCritical(array, &isCopy);
    if (data == nullptr) return;
    secure_zero(data, static_cast<size_t>(len));
    // Mode 0: if the VM handed us a copy, it is copied back (zeroed) and freed.
    env->ReleasePrimitiveArrayCritical(array, data, 0);
}

