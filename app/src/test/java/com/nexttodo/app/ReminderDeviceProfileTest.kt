package com.nexttodo.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
internal class ReminderDeviceProfileTest(private val manufacturer: String, private val brand: String,
    private val expected: ReminderDeviceProfile) {
    @Test fun choosesRelevantGuidanceAndSafeGenericFallback() {
        assertEquals(expected, reminderDeviceProfile(manufacturer, brand))
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}/{1}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf("HONOR", "HONOR", ReminderDeviceProfile.HONOR),
            arrayOf("HUAWEI", "HONOR", ReminderDeviceProfile.HONOR),
            arrayOf("HUAWEI", "HUAWEI", ReminderDeviceProfile.HUAWEI),
            arrayOf("Xiaomi", "Xiaomi", ReminderDeviceProfile.XIAOMI),
            arrayOf("Xiaomi", "Redmi", ReminderDeviceProfile.XIAOMI),
            arrayOf("Xiaomi", "POCO", ReminderDeviceProfile.XIAOMI),
            arrayOf("OPPO", "OPPO", ReminderDeviceProfile.OPLUS),
            arrayOf("OPPO", "OnePlus", ReminderDeviceProfile.OPLUS),
            arrayOf("realme", "realme", ReminderDeviceProfile.OPLUS),
            arrayOf("OPLUS", "unknown", ReminderDeviceProfile.OPLUS),
            arrayOf("vivo", "vivo", ReminderDeviceProfile.VIVO),
            arrayOf("BBK", "iQOO", ReminderDeviceProfile.VIVO),
            arrayOf(" Samsung ", " Galaxy ", ReminderDeviceProfile.SAMSUNG),
            arrayOf("Google", "google", ReminderDeviceProfile.GOOGLE),
            arrayOf("Google", "Pixel", ReminderDeviceProfile.GOOGLE),
            arrayOf("motorola", "motorola", ReminderDeviceProfile.MOTOROLA),
            arrayOf("LENOVO", "Lenovo", ReminderDeviceProfile.MOTOROLA),
            arrayOf("Lenovo", "ZUK", ReminderDeviceProfile.MOTOROLA),
            arrayOf("Meizu", "meizu", ReminderDeviceProfile.MEIZU),
            arrayOf("ZTE", "ZTE", ReminderDeviceProfile.ZTE),
            arrayOf("nubia", "nubia", ReminderDeviceProfile.ZTE),
            arrayOf("Nubia", "REDMAGIC", ReminderDeviceProfile.ZTE),
            arrayOf("asus", "ASUS", ReminderDeviceProfile.ASUS),
            arrayOf("ASUSTeK COMPUTER INC.", "ROG", ReminderDeviceProfile.ASUS),
            arrayOf("Sony", "Sony", ReminderDeviceProfile.SONY),
            arrayOf("TECNO MOBILE LIMITED", "TECNO", ReminderDeviceProfile.TRANSSION),
            arrayOf("Infinix", "Infinix", ReminderDeviceProfile.TRANSSION),
            arrayOf("itel", "itel", ReminderDeviceProfile.TRANSSION),
            arrayOf("HMD Global", "Nokia", ReminderDeviceProfile.HMD),
            arrayOf("HMD Global", "HMD", ReminderDeviceProfile.HMD),
            arrayOf("Nothing", "Nothing", ReminderDeviceProfile.NOTHING),
            arrayOf("Nothing", "CMF", ReminderDeviceProfile.NOTHING),
            arrayOf("", "", ReminderDeviceProfile.GENERIC),
            arrayOf("Acme", "new-brand", ReminderDeviceProfile.GENERIC),
            arrayOf("unknown-huawei-like", "unknown", ReminderDeviceProfile.GENERIC),
            arrayOf("Google", "Android", ReminderDeviceProfile.GOOGLE),
        )
    }
}
