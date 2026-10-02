package com.mobilemcp.pro

/** Distinguish a remote-chatbot capability disclaimer from a real handover. */
object PhoneReplyPolicy {
    fun isCapabilityDenial(text: String): Boolean {
        val value = PersianInput.normalize(text)
        if (listOf("رمز", "گذرواژه", "کد ورود", "احراز هویت", "بانکی", "پرداخت", "کپچا",
                "password", "otp", "captcha", "authentication", "payment", "permission denied")
                .any { value.contains(it) }) return false
        val negative = listOf("نمی توانم", "نمی تونم", "نمیتوانم", "نمیتونم", "قادر نیستم", "ندارم",
            "cannot", "can't", "can not", "don't have access", "unable to")
            .any { value.contains(it) }
        if (!negative) return false
        val device = listOf("گوشی", "تلفن", "صفحه نمایش", "برنامه ها", "اپلیکیشن", "تلگرام", "گوگل", "روبیکا", "phone", "device", "screen", "apps", "telegram", "google", "rubika")
            .any { value.contains(it) }
        val capability = listOf("دسترسی", "کنترل", "باز کنم", "باز کردن", "اجرا", "بروم", "برم", "access", "control", "open", "operate")
            .any { value.contains(it) }
        return device && capability
    }
}
