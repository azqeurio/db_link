/**
 * JNI helper to detach the Linux kernel USB driver from a specific interface.
 *
 * On Samsung Android 16+, the system MTP host driver grabs the PTP bulk-IN
 * endpoint and neither claimInterface(force=true) nor setInterface() can
 * fully release it.  The only reliable way is ioctl(USBDEVFS_DISCONNECT)
 * which explicitly tells the kernel to unbind its driver from the interface.
 *
 * After this call, claimInterface() + bulkTransfer() / UsbRequest will work
 * normally because no kernel driver is competing for the endpoint.
 */
#include <jni.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <android/log.h>
#include <errno.h>
#include <stdbool.h>
#include <string.h>

#define TAG "UsbNative"

static const char *USB_NATIVE_CLASS = "dev/dblink/core/usb/UsbNative";

/*
 * struct usbdevfs_ioctl is used to send a sub-ioctl to a specific interface.
 * We set ioctl_code = USBDEVFS_DISCONNECT to detach the kernel driver.
 */
static jint nativeDisconnectDriver(
        JNIEnv *env, jobject thiz, jint fd, jint interfaceNum) {
    (void) env;
    (void) thiz;

    struct usbdevfs_ioctl command;
    command.ifno = interfaceNum;
    command.ioctl_code = USBDEVFS_DISCONNECT;
    command.data = NULL;

    int result = ioctl(fd, USBDEVFS_IOCTL, &command);
    if (result < 0) {
        int err = errno;
        __android_log_print(ANDROID_LOG_DEBUG, TAG,
            "USBDEVFS_DISCONNECT ifno=%d fd=%d failed: %s (errno=%d)",
            interfaceNum, fd, strerror(err), err);
        return -err;
    }

    __android_log_print(ANDROID_LOG_DEBUG, TAG,
        "USBDEVFS_DISCONNECT ifno=%d fd=%d success", interfaceNum, fd);
    return 0;
}

/**
 * Also provide a "connect" call to re-attach the kernel driver if needed.
 */
static jint nativeConnectDriver(
        JNIEnv *env, jobject thiz, jint fd, jint interfaceNum) {
    (void) env;
    (void) thiz;

    struct usbdevfs_ioctl command;
    command.ifno = interfaceNum;
    command.ioctl_code = USBDEVFS_CONNECT;
    command.data = NULL;

    int result = ioctl(fd, USBDEVFS_IOCTL, &command);
    if (result < 0) {
        int err = errno;
        __android_log_print(ANDROID_LOG_DEBUG, TAG,
            "USBDEVFS_CONNECT ifno=%d fd=%d failed: %s (errno=%d)",
            interfaceNum, fd, strerror(err), err);
        return -err;
    }

    __android_log_print(ANDROID_LOG_DEBUG, TAG,
        "USBDEVFS_CONNECT ifno=%d fd=%d success", interfaceNum, fd);
    return 0;
}

static jint nativeBulkTransfer(
        JNIEnv *env,
        jobject thiz,
        jint fd,
        jint endpointAddress,
        jbyteArray buffer,
        jint offset,
        jint length,
        jint timeoutMs) {
    (void) thiz;

    if (buffer == NULL) {
        __android_log_print(ANDROID_LOG_DEBUG, TAG,
            "USBDEVFS_BULK ep=0x%x fd=%d failed: null buffer", endpointAddress, fd);
        return -EINVAL;
    }

    jsize arrayLength = (*env)->GetArrayLength(env, buffer);
    if (offset < 0 || length < 0 || offset > arrayLength || length > arrayLength - offset) {
        __android_log_print(ANDROID_LOG_DEBUG, TAG,
            "USBDEVFS_BULK ep=0x%x fd=%d failed: invalid range offset=%d length=%d size=%d",
            endpointAddress, fd, offset, length, arrayLength);
        return -EINVAL;
    }

    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (bytes == NULL) {
        __android_log_print(ANDROID_LOG_DEBUG, TAG,
            "USBDEVFS_BULK ep=0x%x fd=%d failed: GetByteArrayElements returned null",
            endpointAddress, fd);
        return -ENOMEM;
    }

    struct usbdevfs_bulktransfer transfer;
    memset(&transfer, 0, sizeof(transfer));
    transfer.ep = (unsigned int) endpointAddress;
    transfer.len = (unsigned int) length;
    transfer.timeout = (unsigned int) timeoutMs;
    transfer.data = bytes + offset;

    int result = ioctl(fd, USBDEVFS_BULK, &transfer);
    int releaseMode = ((endpointAddress & 0x80) != 0) ? 0 : JNI_ABORT;
    if (result < 0) {
        int err = errno;
        __android_log_print(ANDROID_LOG_DEBUG, TAG,
            "USBDEVFS_BULK ep=0x%x fd=%d failed: %s (errno=%d) timeout=%d len=%d",
            endpointAddress, fd, strerror(err), err, timeoutMs, length);
        (*env)->ReleaseByteArrayElements(env, buffer, bytes, releaseMode);
        return -err;
    }

    (*env)->ReleaseByteArrayElements(env, buffer, bytes, releaseMode);
    return result;
}

static bool registerUsbNativeMethods(JNIEnv *env) {
    const JNINativeMethod methods[] = {
        {
            "nativeDisconnectDriver",
            "(II)I",
            (void *) nativeDisconnectDriver,
        },
        {
            "nativeConnectDriver",
            "(II)I",
            (void *) nativeConnectDriver,
        },
        {
            "nativeBulkTransfer",
            "(II[BIII)I",
            (void *) nativeBulkTransfer,
        },
    };

    jclass clazz = (*env)->FindClass(env, USB_NATIVE_CLASS);
    if (clazz == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "RegisterNatives failed: class %s not found", USB_NATIVE_CLASS);
        return false;
    }

    const jint result = (*env)->RegisterNatives(
        env,
        clazz,
        methods,
        sizeof(methods) / sizeof(methods[0]));
    (*env)->DeleteLocalRef(env, clazz);
    if (result != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "RegisterNatives failed for %s: %d", USB_NATIVE_CLASS, result);
        return false;
    }
    return true;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;

    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK || env == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "JNI_OnLoad failed: GetEnv");
        return JNI_ERR;
    }

    if (!registerUsbNativeMethods(env)) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
