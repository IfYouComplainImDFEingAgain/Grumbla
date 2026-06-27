package app.notmumla.di

import android.content.Context
import androidx.room.Room
import app.notmumla.data.db.AppDatabase
import app.notmumla.data.db.ServerDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "not-mumla.db").build()

    @Provides
    fun provideServerDao(db: AppDatabase): ServerDao = db.serverDao()
}
