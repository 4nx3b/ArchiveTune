/*
 * Hilt wiring for the ported LastWave-native audio stack: the shared
 * DataStore for its preference classes and the application scope its
 * collectors run on. Classes with @Singleton @Inject constructors
 * (NativeAudioEngine, EqualizerPreferences, ExclusiveUsbOutput,
 * UsbDacMonitor, LoudnessPrefs, UsbExclusiveState) bind themselves.
 */

package moe.rukamori.archivetune.di

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.DataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import moe.rukamori.archivetune.utils.dataStore

@Module
@InstallIn(SingletonComponent::class)
object LastwaveModule {

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.applicationContext.dataStore
}
