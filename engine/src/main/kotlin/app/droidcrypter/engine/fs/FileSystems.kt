package app.droidcrypter.engine.fs

import app.droidcrypter.engine.BlockDevice

object FileSystems {
    /** Picks a driver by boot-sector OEM name: "EXFAT   " → exFAT, "NTFS    " → NTFS, otherwise FAT. */
    fun open(dev: BlockDevice): FileSystemDriver {
        val bs = ByteArray(512)
        dev.readFully(0, bs, 0, 512)
        return when (String(bs, 3, 8, Charsets.US_ASCII)) {
            "EXFAT   " -> ExFatFileSystem(dev)
            "NTFS    " -> NtfsFileSystem(dev)
            else -> FatFileSystem(dev)
        }
    }
}

