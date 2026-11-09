package kr.phishguard.app.scan

/** 권한 문자열 → 분류 (Android 의존성 없음) */
object PermissionMap {
    private val map: Map<String, PermCategory> = mapOf(
        "android.permission.READ_SMS" to PermCategory.SMS,
        "android.permission.RECEIVE_SMS" to PermCategory.SMS,
        "android.permission.SEND_SMS" to PermCategory.SMS,
        "android.permission.RECEIVE_MMS" to PermCategory.SMS,
        "android.permission.RECEIVE_WAP_PUSH" to PermCategory.SMS,
        "android.permission.READ_CALL_LOG" to PermCategory.CALL_LOG,
        "android.permission.WRITE_CALL_LOG" to PermCategory.CALL_LOG,
        "android.permission.PROCESS_OUTGOING_CALLS" to PermCategory.CALL_LOG,
        "android.permission.CALL_PHONE" to PermCategory.PHONE,
        "android.permission.READ_PHONE_STATE" to PermCategory.PHONE,
        "android.permission.READ_PHONE_NUMBERS" to PermCategory.PHONE,
        "android.permission.ANSWER_PHONE_CALLS" to PermCategory.PHONE,
        "android.permission.READ_CONTACTS" to PermCategory.CONTACTS,
        "android.permission.WRITE_CONTACTS" to PermCategory.CONTACTS,
        "android.permission.GET_ACCOUNTS" to PermCategory.CONTACTS,
        "android.permission.SYSTEM_ALERT_WINDOW" to PermCategory.OVERLAY,
        "android.permission.REQUEST_INSTALL_PACKAGES" to PermCategory.INSTALL_PACKAGES,
        "android.permission.CAMERA" to PermCategory.CAMERA_MIC,
        "android.permission.RECORD_AUDIO" to PermCategory.CAMERA_MIC,
        "android.permission.ACCESS_FINE_LOCATION" to PermCategory.LOCATION,
        "android.permission.ACCESS_COARSE_LOCATION" to PermCategory.LOCATION,
        "android.permission.ACCESS_BACKGROUND_LOCATION" to PermCategory.LOCATION,
        "android.permission.READ_EXTERNAL_STORAGE" to PermCategory.STORAGE,
        "android.permission.WRITE_EXTERNAL_STORAGE" to PermCategory.STORAGE,
        "android.permission.MANAGE_EXTERNAL_STORAGE" to PermCategory.STORAGE,
        "android.permission.READ_MEDIA_IMAGES" to PermCategory.STORAGE,
        "android.permission.READ_MEDIA_VIDEO" to PermCategory.STORAGE,
        "android.permission.READ_MEDIA_AUDIO" to PermCategory.STORAGE,
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED" to PermCategory.STORAGE,
        "android.permission.BIND_ACCESSIBILITY_SERVICE" to PermCategory.ACCESSIBILITY,
        "android.permission.BIND_DEVICE_ADMIN" to PermCategory.DEVICE_ADMIN,
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" to PermCategory.NOTIFICATION_LISTENER,
    )

    fun categoryOf(permission: String): PermCategory? = map[permission]

    /** 허용 시 위험으로 표시할 분류 */
    val DANGEROUS: Set<PermCategory> = setOf(
        PermCategory.SMS, PermCategory.CALL_LOG, PermCategory.PHONE, PermCategory.CONTACTS,
        PermCategory.ACCESSIBILITY, PermCategory.DEVICE_ADMIN, PermCategory.OVERLAY,
        PermCategory.NOTIFICATION_LISTENER, PermCategory.INSTALL_PACKAGES,
    )

    /** "pkg/cls:pkg2/cls2" 형태 설정 문자열에 패키지가 포함되는지 */
    fun settingListContains(setting: String?, packageName: String): Boolean {
        if (setting.isNullOrEmpty()) return false
        return setting.split(':').any { entry -> entry.substringBefore('/').trim() == packageName }
    }
}
