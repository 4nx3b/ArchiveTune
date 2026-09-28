/*
 * Hilt wiring for the ported Tryptify audio stack. Classes that carry their
 * own @Singleton @Inject constructors (the processors, Oxford effects,
 * repositories, LibusbUacDriver, UsbExclusiveController, the ViewModels)
 * bind themselves; this module provides the pieces that don't: the
 * preference facade, the file-backed DAOs, the measurement APIs and the
 * performance profile.
 */

package moe.rukamori.archivetune.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import tf.monochrome.android.data.db.dao.EqPresetDao
import tf.monochrome.android.data.db.dao.FileBackedEqPresetDao
import tf.monochrome.android.data.db.dao.FileBackedMixPresetDao
import tf.monochrome.android.data.db.dao.MixPresetDao
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.api.HeadphoneAutoEqApi
import tf.monochrome.android.data.api.SquiglinkApi
import tf.monochrome.android.performance.PerformanceProfile

@Module
@InstallIn(SingletonComponent::class)
object TryptifyModule {

    @Provides
    @Singleton
    fun provideTryptifyPreferences(@ApplicationContext context: Context): PreferencesManager =
        PreferencesManager(context)

    // The repositories and ViewModels inject the DAO INTERFACES (Tryptify's
    // own types), so the providers bind the interfaces to the file-backed
    // implementations.
    @Provides
    @Singleton
    fun provideEqPresetDao(@ApplicationContext context: Context): EqPresetDao =
        FileBackedEqPresetDao(context)

    @Provides
    @Singleton
    fun provideMixPresetDao(@ApplicationContext context: Context): MixPresetDao =
        FileBackedMixPresetDao(context)

    @Provides
    @Singleton
    fun provideHeadphoneAutoEqApi(): HeadphoneAutoEqApi = HeadphoneAutoEqApi()

    @Provides
    @Singleton
    fun provideSquiglinkApi(): SquiglinkApi = SquiglinkApi()

    @Provides
    @Singleton
    fun providePerformanceProfile(): PerformanceProfile = PerformanceProfile.HIGH
}
