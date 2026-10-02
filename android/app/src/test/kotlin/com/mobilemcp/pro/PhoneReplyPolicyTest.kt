package com.mobilemcp.pro

import org.junit.Assert.*
import org.junit.Test

class PhoneReplyPolicyTest {
    @Test fun recognizesFalseRuntimeCapabilityDisclaimers() {
        assertTrue(PhoneReplyPolicy.isCapabilityDenial("من به گوشی شما دسترسی ندارم."))
        assertTrue(PhoneReplyPolicy.isCapabilityDenial("نمی‌توانم برنامه‌ها را باز کنم."))
        assertTrue(PhoneReplyPolicy.isCapabilityDenial("نمی‌تونم برم گوگل"))
        assertTrue(PhoneReplyPolicy.isCapabilityDenial("I cannot open apps on your phone."))
    }
    @Test fun preservesAuthenticationAndRealProtectedStepHandovers() {
        assertFalse(PhoneReplyPolicy.isCapabilityDenial("برای ورود به تلگرام به رمز نیاز است؛ من نمی‌توانم آن را وارد کنم."))
        assertFalse(PhoneReplyPolicy.isCapabilityDenial("I cannot unlock your phone or enter an OTP."))
        assertFalse(PhoneReplyPolicy.isCapabilityDenial("نمی‌توانم پرداخت بانکی را انجام دهم."))
    }
    @Test fun doesNotRewriteOrdinaryRepliesOrSpecificActionErrors() {
        assertFalse(PhoneReplyPolicy.isCapabilityDenial("روبیکا نصب نیست."))
        assertFalse(PhoneReplyPolicy.isCapabilityDenial("تلگرام را باز کردم."))
        assertFalse(PhoneReplyPolicy.isCapabilityDenial("نمی‌توانم آینده را پیش‌بینی کنم."))
    }
}
