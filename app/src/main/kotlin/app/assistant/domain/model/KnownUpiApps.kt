package app.assistant.domain.model

/**
 * The UPI apps Apex knows by package name, so they can be switched on in one tap. Any other app can
 * still be added from the installed-apps list; the generic parser reads it the same way.
 */
object KnownUpiApps {
    val PACKAGES: Map<String, String> = linkedMapOf(
        "com.google.android.apps.nbu.paisa.user" to "Google Pay",
        "com.phonepe.app" to "PhonePe",
        "net.one97.paytm" to "Paytm",
        "in.org.npci.upiapp" to "BHIM",
        "com.dreamplug.androidapp" to "CRED",
        "in.amazon.mShop.android.shopping" to "Amazon Pay",
        "com.mobikwik_new" to "MobiKwik",
        "com.freecharge.android" to "Freecharge",
        "money.super.payments" to "super.money",
        "com.navi.android" to "Navi",
        "com.whatsapp" to "WhatsApp Pay",
        "com.sbi.upi" to "BHIM SBI Pay",
        "com.csam.icici.bank.imobile" to "iMobile Pay",
        "com.snapwork.hdfc" to "HDFC Bank",
        "com.axis.mobile" to "Axis Mobile",
        "com.myairtelapp" to "Airtel Thanks",
    )
}
