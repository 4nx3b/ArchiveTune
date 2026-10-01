package tf.monochrome.android.audio.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import tf.monochrome.android.data.preferences.PreferencesManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsbExclusiveController @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val driver: LibusbUacDriver,
    private val preferences: PreferencesManager,
) {
    enum class Status {
        Disabled,
        NoDevice,
        AwaitingPermission,

        DeviceOpen,

        InterfaceClaimed,

        Streaming,

        Error,
    }

    private val _status = MutableStateFlow(Status.Disabled)
    val status: StateFlow<Status> = _status.asStateFlow()

    val diagnostics: StateFlow<BypassDiagnostics?> = driver.diagnostics

    val lastStartError: StateFlow<StartFailure?> = driver.lastStartError

    val supportedRates: StateFlow<List<ClockRateRange>> = driver.supportedRates

    val dacInfo: StateFlow<DacInfo?> = driver.dacInfo

    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var enabled = false
    private val tick = Channel<Unit>(Channel.CONFLATED)

    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> tick.trySend(Unit)
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(attachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(attachReceiver, filter)
        }

        scope.launch {
            preferences.usbExclusiveBitPerfectEnabled
                .distinctUntilChanged()
                .collect {
                    enabled = it
                    tick.trySend(Unit)
                }
        }
        scope.launch {
            for (ignored in tick) reconcile()
        }

        scope.launch {
            driver.isStreaming.collect { streaming ->
                if (streaming) {
                    _status.value = Status.Streaming
                } else if (_status.value == Status.Streaming) {
                    _status.value = if (driver.isOpen.value) Status.DeviceOpen else Status.NoDevice
                }
            }
        }

        scope.launch {
            driver.lastStartError.collect { err ->
                if (err != null && enabled && !driver.isStreaming.value) {
                    _status.value = Status.Error
                }
            }
        }
    }

    private suspend fun reconcile() {
        if (!enabled) {
            if (driver.isOpen.value) driver.close()
            _status.value = Status.Disabled
            return
        }
        val device = findAudioDevice() ?: run {
            if (driver.isOpen.value) driver.close()
            _status.value = Status.NoDevice
            return
        }
        if (!usbManager.hasPermission(device)) {
            _status.value = Status.AwaitingPermission
            val granted = driver.requestPermission(device)
            if (!granted) {
                _status.value = Status.AwaitingPermission
                return
            }
        }
        val opened = driver.open(device)
        if (!opened) {
            _status.value = Status.Error
            Log.w(TAG,
                "driver.open failed — likely Developer Options → " +
                "Disable USB audio routing is OFF (kernel UAC driver " +
                "still owns the streaming interface)."
            )
            return
        }
        _status.value = Status.DeviceOpen
    }

    private fun findAudioDevice(): UsbDevice? {
        for (dev in usbManager.deviceList.values) {
            for (i in 0 until dev.interfaceCount) {
                if (dev.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                    return dev
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "UsbExclusiveCtl"
    }
}
