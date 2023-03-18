package br.net.ari.lprfiscalcam.enums

enum class ImageFormat(val code: Int) {
    /**
     * 1 byte per pixel grayscale
     */
    Gray8(0),

    /**
     * 3 bytes per pixel
     */
    RGB(1),

    /**
     * 24 bits per pixel MS Windows Bitmap
     */
    BMP24(2),

    /**
     * jpeg format
     */
    JPEG(3),

    /**
     * 16 bits per pixel YUV 4:2:2 (Y sampled at every pixel, U and V sampled at every second pixel horizontally on each line)
     */
    UYVY(4),

    /**
     * 16 bits per pixel YUV 4:2:2 (as for UYVY but with different component ordering)
     */
    YUY2(5),

    /**
     * 12 Bits per Pixel, 4:2:0 Format, Related to I420, NV12 has one luma plane Y and one plane with U and V values interleaved
     */
    NV12(6),

    /**
     * 12 Bits per Pixel, 4:2:0 Format. It has the luma "luminance" plane Y first, then the U chroma plane and last the V chroma plane
     */
    I420(7),

    /**
     * 32 bits per Pixel, BAYER format RGGB color space
     */
    BAYER_RGGB(8),

    /**
     * 12 Bits per Pixel, 4:2:0 Format, Related to I420, NV21 has one luma plane Y and one plane with V and U values interleaved
     */
    YUV(9);


    companion object {
        fun getFormat(code: Int) = ImageFormat.values().find { code == it.code }
    }
}
