# Правила R8 для «Просто ТВ».
#
# Своих правил сохранения не нужно: Hilt, Room, WorkManager, Media3, OkHttp, Coil
# и nextlib приносят правила в своих библиотеках, а рефлексии в коде приложения нет.

# Номера строк в отчётах о падениях, без настоящих имён исходных файлов.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
