package me.zyrouge.symphony.metaphony

/** Write only to a staged copy; the application owns backup/verification/commit. */
object AudioMetadataWriter {
    const val WRITTEN = 0
    const val ALREADY_HAS_LYRICS = 1
    const val UNSUPPORTED = -1
    const val FAILED = -2
    init { System.loadLibrary("metaphony") }
    external fun writeLyrics(filename: String, fd: Int, utf8Lyrics: ByteArray): Int
}
