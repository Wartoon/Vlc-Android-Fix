from pathlib import Path

TARGET = Path("application/vlc-android/src/org/videolan/vlc/gui/preferences/PreferencesAdvanced.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


text = TARGET.read_text()

text = replace_once(
    text,
    '''    private fun fullBackupFile(): File =
        File(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY + "/VLC-Full-Backup.zip")''',
    '''    private fun dualVariantForPackage(packageName: String): String? = when (packageName) {
        "org.videolan.vlc.internalstorage" -> "internal"
        "org.videolan.vlc.smb" -> "smb"
        else -> null
    }

    private fun fullBackupFile(): File {
        val name = when (dualVariantForPackage(requireContext().packageName)) {
            "internal" -> "VLC Complete Backup - Internal Storage.zip"
            "smb" -> "VLC Complete Backup - SMB.zip"
            else -> throw IllegalStateException("Unsupported VLC Dual package")
        }
        return File(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY + "/" + name)
    }''',
    "variant backup filename",
)

text = replace_once(
    text,
    '''                        val manifest = """{"format":1,"package":"${context.packageName}","vlc":"3.7.2 Beta 2"}"""''',
    '''                        val sourceVariant = dualVariantForPackage(context.packageName)
                            ?: throw IOException("Unsupported VLC Dual package")
                        val manifest = """{"format":2,"appFamily":"vlc-dual","variant":"$sourceVariant","package":"${context.packageName}","vlc":"3.7.2 Beta 2","components":["settings","mediaDb","appDb","artwork","subtitles","credentials"]}"""''',
    "format 2 manifest",
)

text = replace_once(
    text,
    '''                    val manifestText = manifest.readText()
                    if (!manifestText.contains("\\\"format\\\":1") ||
                        !manifestText.contains("\\\"package\\\":\\\"${context.packageName}\\\""))
                        throw IOException("Unsupported or incompatible backup")''',
    '''                    val manifestText = manifest.readText()
                    val sourcePackage = when {
                        manifestText.contains("\\\"package\\\":\\\"org.videolan.vlc.internalstorage\\\"") -> "org.videolan.vlc.internalstorage"
                        manifestText.contains("\\\"package\\\":\\\"org.videolan.vlc.smb\\\"") -> "org.videolan.vlc.smb"
                        else -> throw IOException("Backup package is not part of VLC Dual")
                    }
                    val sourceVariant = dualVariantForPackage(sourcePackage)
                        ?: throw IOException("Backup package is not part of VLC Dual")
                    if (!manifestText.contains("\\\"format\\\":2") ||
                        !manifestText.contains("\\\"appFamily\\\":\\\"vlc-dual\\\"") ||
                        !manifestText.contains("\\\"variant\\\":\\\"$sourceVariant\\\""))
                        throw IOException("Unsupported or incompatible backup")''',
    "dual-family manifest validation",
)

TARGET.write_text(text)
print(f"applied portable dual backup contract to {TARGET}")
