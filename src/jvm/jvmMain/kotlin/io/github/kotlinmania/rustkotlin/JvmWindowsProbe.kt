package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.CREATE_ALWAYS
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.CREATE_NEW
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.FILE_ATTRIBUTE_DIRECTORY
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.FILE_ATTRIBUTE_NORMAL
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.FILE_ATTRIBUTE_READONLY
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.FILE_GENERIC_READ
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.FILE_GENERIC_WRITE
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.FILE_SHARE_READ
import io.github.kotlinmania.windowssys.windows.win32.storage.filesystem.FILE_SHARE_WRITE

internal object JvmWindowsFileProbe : WindowsTargetFileProbe(
    attrDirectory = FILE_ATTRIBUTE_DIRECTORY,
    attrNormal = FILE_ATTRIBUTE_NORMAL,
    attrReadOnly = FILE_ATTRIBUTE_READONLY,
    createAlways = CREATE_ALWAYS,
    createNew = CREATE_NEW,
    desiredAccess = FILE_GENERIC_READ or FILE_GENERIC_WRITE,
    shareMode = FILE_SHARE_READ or FILE_SHARE_WRITE,
)
