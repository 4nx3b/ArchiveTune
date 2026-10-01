

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
