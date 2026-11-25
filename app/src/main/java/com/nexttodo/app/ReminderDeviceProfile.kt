package com.nexttodo.app

import java.util.Locale

internal data class ReminderSettingsPage(val packageName: String, val className: String)

internal enum class ReminderDeviceProfile(val label: String, val guidance: String,
    val startupPages: List<ReminderSettingsPage> = emptyList()) {
    HONOR("荣耀", "在 MagicOS 的应用启动管理中关闭本应用的自动管理，允许自启动、关联启动和后台活动；同时检查电池优化及锁屏通知。", listOf(
        ReminderSettingsPage("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ReminderSettingsPage("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))),
    HUAWEI("华为", "在 EMUI／HarmonyOS 的应用启动管理中改为手动管理，允许自启动、关联启动和后台活动，并检查锁屏通知。仅适用于仍能运行 Android APK 的系统。", listOf(
        ReminderSettingsPage("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))),
    XIAOMI("小米／Redmi／POCO", "在 HyperOS／MIUI 中允许自启动，将本应用省电策略设为无限制；在其他权限或通知管理中检查后台弹出、锁屏显示及悬浮通知。", listOf(
        ReminderSettingsPage("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))),
    OPLUS("OPPO／一加／realme", "在 ColorOS／OxygenOS／realme UI 的应用管理或电池管理中允许自启动和后台活动，检查后台耗电限制、锁屏及横幅通知。", listOf(
        ReminderSettingsPage("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
        ReminderSettingsPage("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ReminderSettingsPage("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ReminderSettingsPage("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"))),
    VIVO("vivo／iQOO", "在 OriginOS／Funtouch OS 的后台耗电管理中允许后台高耗电，并在自启动、后台弹出界面和通知管理中允许本应用提醒。", listOf(
        ReminderSettingsPage("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ReminderSettingsPage("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))),
    SAMSUNG("三星", "在 One UI 的电池／后台使用限制中将本应用加入从不休眠的应用，并检查单独的通知类别、锁屏及弹出通知。", listOf(
        ReminderSettingsPage("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        ReminderSettingsPage("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"))),
    GOOGLE("Google／Pixel", "在应用电池用量中允许后台使用，按需设为不受限制；检查通知类别、锁屏显示和全屏提醒授权。"),
    MOTOROLA("Motorola／联想", "在应用电池设置中允许后台活动，检查自启动或后台管理、通知类别和锁屏显示；菜单随系统版本变化。"),
    MEIZU("魅族", "在 Flyme 的手机管家／权限管理中允许自启动、后台运行，并检查锁屏和悬浮通知。", listOf(
        ReminderSettingsPage("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"))),
    ZTE("中兴／努比亚／红魔", "在应用电池、后台管理或手机管家中允许自启动和后台运行，关闭对本应用的省电限制，检查锁屏通知。"),
    ASUS("ASUS／ROG", "检查电池优化及自动启动管理，允许本应用后台运行，并开启提醒类别和锁屏通知。"),
    SONY("Sony", "检查 STAMINA／省电模式及应用电池限制，允许本应用后台运行，并检查锁屏通知和提醒类别。"),
    TRANSSION("TECNO／Infinix／itel", "检查 Phone Master 或系统电池管理中的自启动、后台运行及省电限制，并允许锁屏和弹出通知。"),
    HMD("Nokia／HMD", "检查应用电池优化及后台活动限制，允许通知类别、锁屏显示和全屏提醒。"),
    NOTHING("Nothing／CMF", "检查应用电池用量、后台使用及省电模式，允许通知类别、锁屏显示和全屏提醒。"),
    GENERIC("通用 Android", "在应用详情的电池设置中允许后台运行；如系统另有自启动、后台弹出或锁屏通知开关，也需要手动检查。未知品牌同样使用通用授权和提醒流程。");
}

internal fun reminderDeviceProfile(manufacturer: String, brand: String): ReminderDeviceProfile {
    fun normalize(value: String) = value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    val names = listOf(normalize(brand), normalize(manufacturer))
    // Prefer the marketed brand when the manufacturer is a parent company.
    for (name in names) {
        when {
            name.startsWith("honor") -> return ReminderDeviceProfile.HONOR
            name.startsWith("huawei") -> return ReminderDeviceProfile.HUAWEI
            listOf("xiaomi", "redmi", "poco").any(name::startsWith) -> return ReminderDeviceProfile.XIAOMI
            listOf("oppo", "oplus", "oneplus", "realme").any(name::startsWith) -> return ReminderDeviceProfile.OPLUS
            listOf("vivo", "iqoo").any(name::startsWith) -> return ReminderDeviceProfile.VIVO
            name.startsWith("samsung") -> return ReminderDeviceProfile.SAMSUNG
            listOf("google", "pixel").any(name::startsWith) -> return ReminderDeviceProfile.GOOGLE
            listOf("motorola", "lenovo", "zuk").any(name::startsWith) -> return ReminderDeviceProfile.MOTOROLA
            name.startsWith("meizu") -> return ReminderDeviceProfile.MEIZU
            listOf("zte", "nubia", "redmagic").any(name::startsWith) -> return ReminderDeviceProfile.ZTE
            listOf("asus", "rog").any(name::startsWith) -> return ReminderDeviceProfile.ASUS
            name.startsWith("sony") -> return ReminderDeviceProfile.SONY
            listOf("tecno", "infinix", "itel", "transsion").any(name::startsWith) -> return ReminderDeviceProfile.TRANSSION
            listOf("nokia", "hmd").any(name::startsWith) -> return ReminderDeviceProfile.HMD
            listOf("nothing", "cmf").any(name::startsWith) -> return ReminderDeviceProfile.NOTHING
        }
    }
    return ReminderDeviceProfile.GENERIC
}
