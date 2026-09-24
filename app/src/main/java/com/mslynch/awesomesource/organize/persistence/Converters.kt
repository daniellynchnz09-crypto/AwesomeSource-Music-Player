package com.mslynch.awesomesource.organize.persistence

import androidx.room.TypeConverter
import com.mslynch.awesomesource.organize.model.FileStatus
import com.mslynch.awesomesource.organize.model.LibraryType
import com.mslynch.awesomesource.organize.model.MetadataSource

class Converters {
    @TypeConverter
    fun fromFileStatus(value: FileStatus): String = value.name

    @TypeConverter
    fun toFileStatus(value: String): FileStatus = FileStatus.valueOf(value)

    @TypeConverter
    fun fromMetadataSource(value: MetadataSource): String = value.name

    @TypeConverter
    fun toMetadataSource(value: String): MetadataSource = MetadataSource.valueOf(value)

    @TypeConverter
    fun fromLibraryType(value: LibraryType?): String? = value?.name

    @TypeConverter
    fun toLibraryType(value: String?): LibraryType? = value?.let { LibraryType.valueOf(it) }
}
