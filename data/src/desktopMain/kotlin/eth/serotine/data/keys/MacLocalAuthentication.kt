package eth.serotine.data.keys

import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/**
 * The few `LAContext` calls [MacBiometricKey] needs, sent through the Objective-C runtime with
 * JNA: LocalAuthentication has no C API, and like [MacSecurity] this needs no native build step.
 * Only plain-argument messages are sent (no blocks): the prompt itself is shown by
 * Security.framework when it uses a key under access control with a context
 * ([MacSecurity.restoreEnclaveKey]), saying the context's [setReason].
 *
 * A context is an Objective-C object this process owns from [newContext] until [release].
 * Objects the framework hands back autoreleased are read inside an autorelease pool of their
 * own ([autoreleasing]), since a JVM thread has none; so is what the frameworks autorelease
 * while a key is used with a context.
 */
internal class MacLocalAuthentication private constructor() {

    private val objc = NativeLibrary.getInstance(OBJC)

    // Loaded for its LAContext class; never called directly.
    @Suppress("unused")
    private val localAuthentication = NativeLibrary.getInstance(LOCAL_AUTHENTICATION)
    private val send: Function = objc.getFunction("objc_msgSend")
    private val getClass: Function = objc.getFunction("objc_getClass")
    private val registerName: Function = objc.getFunction("sel_registerName")
    private val poolPush: Function = objc.getFunction("objc_autoreleasePoolPush")
    private val poolPop: Function = objc.getFunction("objc_autoreleasePoolPop")

    private val contextClass = checkNotNull(getClass.invokePointer(arrayOf("LAContext"))) { "no LAContext class" }
    private val new = selector("new")
    private val release = selector("release")
    private val invalidate = selector("invalidate")
    private val canEvaluatePolicy = selector("canEvaluatePolicy:error:")
    private val evaluatedPolicyDomainState = selector("evaluatedPolicyDomainState")
    private val setLocalizedReason = selector("setLocalizedReason:")
    private val setLocalizedFallbackTitle = selector("setLocalizedFallbackTitle:")
    private val code = selector("code")
    private val length = selector("length")
    private val bytes = selector("bytes")
    private val stringWithUtf8 = selector("stringWithUTF8String:")
    private val stringClass = checkNotNull(getClass.invokePointer(arrayOf("NSString"))) { "no NSString class" }

    private fun selector(name: String): Pointer = checkNotNull(registerName.invokePointer(arrayOf(name))) { name }

    /** A new `LAContext`, which the caller [release]s. */
    fun newContext(): Pointer =
        checkNotNull(send.invokePointer(arrayOf(contextClass, new))) { "[LAContext new] failed" }

    /** Null when [context] can evaluate the biometric policy now, else LAError's code ([LaError]). */
    fun canEvaluate(context: Pointer): Long? = autoreleasing {
        val error = PointerByReference()
        // BOOL is one byte: what the register holds above it is not the answer.
        val can = send.invokeInt(arrayOf(context, canEvaluatePolicy, BIOMETRICS_POLICY, error)) and 0xff
        when {
            can != 0 -> null
            else -> error.value?.let { send.invokeLong(arrayOf(it, code)) } ?: LaError.BIOMETRY_NOT_AVAILABLE
        }
    }

    /** The fingerprint of the enrolled set (`evaluatedPolicyDomainState`), after [canEvaluate] on [context]. */
    fun domainState(context: Pointer): ByteArray? = autoreleasing {
        val data = send.invokePointer(arrayOf(context, evaluatedPolicyDomainState)) ?: return@autoreleasing null
        val size = send.invokeLong(arrayOf(data, length)).toInt()
        if (size == 0) ByteArray(0) else send.invokePointer(arrayOf(data, bytes))?.getByteArray(0, size)
    }

    /** What the system's prompt says the use of the key is for. */
    fun setReason(context: Pointer, reason: String) = setString(context, setLocalizedReason, reason)

    /** The title of the prompt's way out, which ends it as [LaError.USER_FALLBACK]. */
    fun setFallbackTitle(context: Pointer, title: String) = setString(context, setLocalizedFallbackTitle, title)

    /** Ends [context]'s prompt, if one is up, as [LaError.APP_CANCEL]; callable from any thread. */
    fun invalidate(context: Pointer) = autoreleasing {
        send.invokeVoid(arrayOf(context, invalidate))
    }

    fun release(context: Pointer) {
        send.invokeVoid(arrayOf(context, release))
    }

    private fun setString(context: Pointer, setter: Pointer, value: String) = autoreleasing {
        val string = checkNotNull(send.invokePointer(arrayOf(stringClass, stringWithUtf8, value))) { "NSString" }
        send.invokeVoid(arrayOf(context, setter, string))
    }

    /**
     * Runs [block] in an autorelease pool of its own: what the frameworks autorelease while it
     * runs, on a JVM thread that has no pool, is released when it ends.
     */
    fun <T> autoreleasing(block: () -> T): T {
        val pool = poolPush.invokePointer(arrayOf<Any>())
        try {
            return block()
        } finally {
            poolPop.invokeVoid(arrayOf(pool))
        }
    }

    companion object {
        private const val OBJC = "/usr/lib/libobjc.A.dylib"
        private const val LOCAL_AUTHENTICATION =
            "/System/Library/Frameworks/LocalAuthentication.framework/LocalAuthentication"
        private const val BIOMETRICS_POLICY = 1L // LAPolicyDeviceOwnerAuthenticationWithBiometrics

        /** The framework, or null off a Mac or when it cannot be loaded; loaded once. */
        val instance: MacLocalAuthentication? by lazy {
            if (!MacSecurity.onMac) return@lazy null
            try {
                MacLocalAuthentication()
            } catch (e: Throwable) {
                println("Punicus: LocalAuthentication.framework cannot be loaded (${e::class.simpleName})")
                null
            }
        }
    }
}
