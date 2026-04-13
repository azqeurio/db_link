@file:Suppress("ConstPropertyName")

package dev.pl36.cameralink.core.usb

/**
 * PTP (Picture Transfer Protocol) constants for USB camera communication.
 *
 * Includes standard PTP/MTP codes and Olympus/OM System vendor extensions
 * gathered from observed device behavior and protocol interoperability work.
 */
object PtpConstants {

    // USB Class
    /** USB interface class for Still Imaging (PTP) devices. */
    const val USB_CLASS_STILL_IMAGE = 6
    const val USB_SUBCLASS_STILL_IMAGE = 1
    const val USB_PROTOCOL_PTP = 1

    // Known Vendor IDs
    /** Legacy Olympus vendor ID. */
    const val VENDOR_ID_OLYMPUS = 0x07B4
    /** OM Digital Solutions vendor ID (used by OM-1 Mark II and newer). */
    const val VENDOR_ID_OM_DIGITAL = 0x33A2

    // Container Types
    const val CONTAINER_TYPE_COMMAND = 1
    const val CONTAINER_TYPE_DATA = 2
    const val CONTAINER_TYPE_RESPONSE = 3
    const val CONTAINER_TYPE_EVENT = 4

    /** Minimum PTP container size (header only, no params). */
    const val CONTAINER_HEADER_SIZE = 12

    // Standard PTP Operation Codes (0x1xxx)
    object Op {
        const val GetDeviceInfo = 0x1001
        const val OpenSession = 0x1002
        const val CloseSession = 0x1003
        const val GetStorageIDs = 0x1004
        const val GetStorageInfo = 0x1005
        const val GetNumObjects = 0x1006
        const val GetObjectHandles = 0x1007
        const val GetObjectInfo = 0x1008
        const val GetObject = 0x1009
        const val GetThumb = 0x100A
        const val DeleteObject = 0x100B
        const val SendObjectInfo = 0x100C
        const val SendObject = 0x100D
        const val InitiateCapture = 0x100E
        const val FormatStore = 0x100F
        const val ResetDevice = 0x1010
        const val GetDevicePropDesc = 0x1014
        const val GetDevicePropValue = 0x1015
        const val SetDevicePropValue = 0x1016
        const val ResetDevicePropValue = 0x1017
        const val TerminateOpenCapture = 0x1018
        const val GetPartialObject = 0x101B
        const val InitiateOpenCapture = 0x101C
    }

    // Standard Response Codes (0x2xxx)
    object Resp {
        const val Undefined = 0x2000
        const val OK = 0x2001
        const val GeneralError = 0x2002
        const val SessionNotOpen = 0x2003
        const val InvalidTransactionID = 0x2004
        const val OperationNotSupported = 0x2005
        const val ParameterNotSupported = 0x2006
        const val IncompleteTransfer = 0x2007
        const val InvalidStorageID = 0x2008
        const val InvalidObjectHandle = 0x2009
        const val DevicePropNotSupported = 0x200A
        const val InvalidObjectFormatCode = 0x200B
        const val StoreFull = 0x200C
        const val ObjectWriteProtected = 0x200D
        const val StoreReadOnly = 0x200E
        const val AccessDenied = 0x200F
        const val NoThumbnailPresent = 0x2010
        const val StoreNotAvailable = 0x2013
        const val SpecificationByFormatUnsupported = 0x2014
        const val NoValidObjectInfo = 0x2015
        const val DeviceBusy = 0x2019
        const val InvalidParentObject = 0x201A
        const val InvalidDevicePropFormat = 0x201B
        const val InvalidDevicePropValue = 0x201C
        const val InvalidParameter = 0x201D
        const val SessionAlreadyOpen = 0x201E
        const val TransactionCancelled = 0x201F
        const val InvalidObjectPropCode = 0xA801

        fun name(code: Int): String = when (code) {
            OK -> "OK"
            GeneralError -> "GeneralError"
            SessionNotOpen -> "SessionNotOpen"
            OperationNotSupported -> "OperationNotSupported"
            DevicePropNotSupported -> "DevicePropNotSupported"
            DeviceBusy -> "DeviceBusy"
            SessionAlreadyOpen -> "SessionAlreadyOpen"
            else -> "0x${code.toString(16).padStart(4, '0')}"
        }
    }

    // Standard Event Codes (0x4xxx)
    object Evt {
        const val CancelTransaction = 0x4001
        const val ObjectAdded = 0x4002
        const val ObjectRemoved = 0x4003
        const val StoreAdded = 0x4004
        const val StoreRemoved = 0x4005
        const val DevicePropChanged = 0x4006
        const val ObjectInfoChanged = 0x4007
        const val DeviceInfoChanged = 0x4008
        const val RequestObjectTransfer = 0x4009
        const val StoreFull = 0x400A
        const val CaptureComplete = 0x400D
    }

    // Standard Device Property Codes (0x5xxx)
    object Prop {
        const val BatteryLevel = 0x5001
        const val FunctionalMode = 0x5002
        const val ImageSize = 0x5003
        const val CompressionSetting = 0x5004
        const val WhiteBalance = 0x5005
        const val FNumber = 0x5007
        const val FocalLength = 0x5008
        const val FocusDistance = 0x5009
        const val FocusMode = 0x500A
        const val ExposureMeteringMode = 0x500B
        const val FlashMode = 0x500C
        const val ExposureTime = 0x500D
        const val ExposureProgramMode = 0x500E
        const val ExposureIndex = 0x500F // ISO
        const val ExposureBiasCompensation = 0x5010
        const val DateTime = 0x5011
        const val CaptureDelay = 0x5012
        const val StillCaptureMode = 0x5013
        const val BurstNumber = 0x5018
        const val FocusMeteringMode = 0x501C
    }

    // Object Format Codes
    object Format {
        const val JPEG = 0x3801
        const val TIFF = 0x380D
        const val RAW = 0x3000 // Generic
        /** OM-1 reports ORF files with this "Undefined" format code instead of 0xB101. */
        const val Undefined = 0x3800
        const val ORF = 0xB101 // Olympus RAW
        const val Association = 0x3001 // Directory
    }

    // Olympus / OM System Vendor Extensions
    // Collected from observed device behavior and public protocol references.

    /**
     * Olympus E-series vendor operations (legacy, but some still used by OM-D).
     */
    object OlympusOp {
        const val Capture = 0x9101
        const val SelfCleaning = 0x9103
        const val SetRGBGain = 0x9106
        const val SetPresetMode = 0x9107
        const val SetWBBiasAll = 0x9108
        /** GetRunMode — retrieves current camera control/run mode. */
        const val GetCameraControlMode = 0x910A
        /** ChangeRunMode — switches camera between STANDALONE, RECORDING, etc. */
        const val SetCameraControlMode = 0x910B
        const val SetWBRGBGain = 0x910C
        const val GetDeviceInfo = 0x9301
        const val OpenSession = 0x9302
        const val SetCameraID = 0x9501
        const val GetCameraID = 0x9581
    }

    /**
     * Olympus OM-D / OM System vendor operations.
     * These are used by OM-1, OM-5, E-M1 series in PTP mode.
     */
    object OmdOp {
        /** Capture on OM-D. Param: 0x0=normal, 0x3=bulb start, 0x6=bulb end */
        const val Capture = 0x9481
        /** One-touch WB gain */
        const val SetOneTouchWBGain = 0x9482
        /** Control live view magnification point */
        const val SetMagnifyingLiveViewPoint = 0x9483
        /** Get a live view frame (JPEG data) */
        const val GetLiveViewImage = 0x9484
        /** Get captured image (JPEG) */
        const val GetImage = 0x9485
        /** Poll for changed device properties */
        const val GetChangedProperties = 0x9486
        /** Manual focus drive (step focus near/far) */
        const val MFDrive = 0x9487
        /** Set multiple device properties at once (batch) */
        const val SetProperties = 0x9489
        /** Query Olympus property-registration hints used with SetProperties */
        const val GetPropertyObserverHints = 0x948B
    }

    /** Capture mode parameters for OmdOp.Capture. */
    object CaptureParam {
        const val NORMAL = 0x0
        /** Half-press / AF start — triggers autofocus at current target */
        const val AF_START = 0x1
        /** Half-press release / AF end */
        const val AF_END = 0x2
        const val BULB_START = 0x3
        const val BULB_END = 0x6
    }

    /**
     * Olympus/OM System vendor event codes (0xC1xx range on OM-1).
     * Cross-checked against OM-1 DeviceInfo event enumeration from real-device logs.
     */
    object OlympusEvt {
        // Some devices expose a legacy 0xC001 form while OM-1 DeviceInfo
        // advertises the newer 0xC1xx form, so accept both.
        const val LegacyCreateRecView = 0xC001
        const val CreateRecView = 0xC101
        const val ObjectAdded = 0xC102
        const val AfFrame = 0xC103
        const val DirectStoreImage = 0xC104
        const val CameraControlOff = 0xC105
        const val AfFrameOverInfo = 0xC106
        const val DevicePropChanged = 0xC108
        const val ImageTransferFinish = 0xC10C
        const val ImageRecordFinish = 0xC10D
        const val SlotStatusChange = 0xC10E
        const val PrioritizeRecord = 0xC10F
        const val FailCombiningAfterShooting = 0xC110
        const val NotifyAfTargetFrame = 0xC111
        const val RawEditParamChanged = 0xC112
        const val NotifyCreatedRawEdit = 0xC113
        const val NotifyThroughCondition = 0xC114
    }

    /**
     * Camera run modes used by Olympus vendor control operations.
     * These control the camera's operating state over PTP.
     */
    object RunMode {
        const val STANDALONE = 0x00
        const val RECORDING = 0x01
        const val RAW_DEVELOP = 0x02
        const val RAW_EDIT = 0x03
        const val RAW_RECORDING_PAUSE = 0x04
    }

    // Olympus Vendor Device Properties
    object OlympusProp {
        // These are vendor-specific property codes (0xD0xx range)
        const val ShutterSpeed = 0xD01C
        const val Aperture = 0xD002
        const val FocusMode = 0xD003
        const val MeteringMode = 0xD004
        const val ISOSpeed = 0xD007
        const val ExposureCompensation = 0xD008
        const val DriveMode = 0xD009
        const val ImageQuality = 0xD00D
        const val WhiteBalance = 0xD01E
        const val FlashMode = Prop.FlashMode
        const val ExposureMode = 0xD01D
        /**
         * Olympus OM-D live view mode property.
         * Tested bodies accept 0x04000300 before live-view frame polling.
         */
        const val LiveViewModeOm = 0xD06D
        const val LiveViewEnabled = 0xD052
        const val FocusDistance = 0xD061
        const val AFResult = 0xD063
        /** AF target area — used to set touch AF point (encoded x/y) */
        const val AFTargetArea = 0xD051
    }

    object OlympusLiveViewMode {
        /**
         * OM-D live view streaming mode used on tested bodies.
         * Used when LiveViewModeOm (0xD06D) is a UINT32 property.
         */
        const val Streaming = 0x04000300

        /**
         * 16-bit variant used when LiveViewModeOm is a UINT16 property.
         */
        const val Streaming16 = 0x0100
    }

    // Helpers
    fun opName(code: Int): String = when (code) {
        Op.GetDeviceInfo -> "GetDeviceInfo"
        Op.OpenSession -> "OpenSession"
        Op.CloseSession -> "CloseSession"
        Op.GetStorageIDs -> "GetStorageIDs"
        Op.GetStorageInfo -> "GetStorageInfo"
        Op.GetNumObjects -> "GetNumObjects"
        Op.GetObjectHandles -> "GetObjectHandles"
        Op.GetObjectInfo -> "GetObjectInfo"
        Op.GetObject -> "GetObject"
        Op.GetThumb -> "GetThumb"
        Op.DeleteObject -> "DeleteObject"
        Op.InitiateCapture -> "InitiateCapture"
        Op.GetDevicePropDesc -> "GetDevicePropDesc"
        Op.GetDevicePropValue -> "GetDevicePropValue"
        Op.SetDevicePropValue -> "SetDevicePropValue"
        Op.GetPartialObject -> "GetPartialObject"
        Op.InitiateOpenCapture -> "InitiateOpenCapture"
        Op.TerminateOpenCapture -> "TerminateOpenCapture"
        OlympusOp.OpenSession -> "Olympus.OpenSession"
        OlympusOp.GetDeviceInfo -> "Olympus.GetDeviceInfo"
        OlympusOp.GetCameraControlMode -> "Oly.GetRunMode"
        OlympusOp.SetCameraControlMode -> "Oly.ChangeRunMode"
        OlympusOp.OpenSession -> "Oly.OpenSession"
        OlympusOp.GetDeviceInfo -> "Oly.GetDeviceInfo"
        OmdOp.Capture -> "OMD.Capture"
        OmdOp.GetLiveViewImage -> "OMD.GetLiveViewImage"
        OmdOp.GetImage -> "OMD.GetImage"
        OmdOp.GetChangedProperties -> "OMD.GetChangedProperties"
        OmdOp.MFDrive -> "OMD.MFDrive"
        OmdOp.SetProperties -> "OMD.SetProperties"
        else -> "0x${code.toString(16).padStart(4, '0')}"
    }
}
