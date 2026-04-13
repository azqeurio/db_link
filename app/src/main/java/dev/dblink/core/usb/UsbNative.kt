package dev.dblink.core.usb

import dev.dblink.core.logging.D

/**
 * JNI bridge for low-level USB operations not exposed by the Android SDK.
 *
 * On Samsung Android 16+, the kernel MTP host driver grabs the PTP bulk-IN
 * endpoint even after [android.hardware.usb.UsbDeviceConnection.claimInterface]
 * with `force = true`. This blocks all bulk IN reads (both `bulkTransfer` and
 * `UsbRequest`).
 *
 * The fix is to explicitly detach the kernel driver via `ioctl(USBDEVFS_DISCONNECT)`
 * before claiming the interface. This is the same mechanism that `libusb` uses
 * on Linux via `libusb_detach_kernel_driver()`.
 *
 * Samsung Android 16 also appears to have a broken Java
 * [android.hardware.usb.UsbDeviceConnection.bulkTransfer] path for some PTP
 * cameras even when the device file descriptor itself is healthy. To bypass
 * that wrapper, we also expose `ioctl(USBDEVFS_BULK)` for raw USB bulk I/O.
 */
object UsbNative {
    private var loaded = false

    init {
        try {
            System.loadLibrary("usbnative")
            loaded = true
            D.usb("UsbNative JNI library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            D.err("USB", "Failed to load usbnative JNI library: ${e.message}")
        }
    }

    /**
     * Detach the kernel USB driver from the given interface.
     *
     * Must be called BEFORE [android.hardware.usb.UsbDeviceConnection.claimInterface].
     *
     * @param fd USB device file descriptor from [android.hardware.usb.UsbDeviceConnection.getFileDescriptor]
     * @param interfaceNum the USB interface number to detach
     * @return 0 on success, negative errno on failure
     */
    fun disconnectKernelDriver(fd: Int, interfaceNum: Int): Int {
        if (!loaded) {
            D.err("USB", "UsbNative not loaded, cannot disconnect kernel driver")
            return -1
        }
        val result = nativeDisconnectDriver(fd, interfaceNum)
        D.usb("disconnectKernelDriver(fd=$fd, iface=$interfaceNum): result=$result")
        return result
    }

    /**
     * Re-attach the kernel USB driver to the given interface.
     * Called during cleanup to restore the system MTP service.
     */
    fun connectKernelDriver(fd: Int, interfaceNum: Int): Int {
        if (!loaded) return -1
        val result = nativeConnectDriver(fd, interfaceNum)
        D.usb("connectKernelDriver(fd=$fd, iface=$interfaceNum): result=$result")
        return result
    }

    /**
     * Run a raw USB bulk transfer directly through `ioctl(USBDEVFS_BULK)`.
     *
     * Returns the transferred byte count, or negative errno on failure.
     */
    fun bulkTransfer(
        fd: Int,
        endpointAddress: Int,
        buffer: ByteArray,
        offset: Int = 0,
        length: Int = buffer.size - offset,
        timeoutMs: Int,
    ): Int {
        if (!loaded) {
            D.err("USB", "UsbNative not loaded, cannot run native bulk transfer")
            return -1
        }
        return nativeBulkTransfer(fd, endpointAddress, buffer, offset, length, timeoutMs)
    }

    val isAvailable: Boolean get() = loaded

    private external fun nativeDisconnectDriver(fd: Int, interfaceNum: Int): Int
    private external fun nativeConnectDriver(fd: Int, interfaceNum: Int): Int
    private external fun nativeBulkTransfer(
        fd: Int,
        endpointAddress: Int,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeoutMs: Int,
    ): Int
}
