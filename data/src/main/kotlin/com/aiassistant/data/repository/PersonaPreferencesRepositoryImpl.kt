package com.aiassistant.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aiassistant.domain.repository.PersonaPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.personaDataStore by preferencesDataStore(name = "persona_preferences")

@Singleton
class PersonaPreferencesRepositoryImpl @Inject constructor(@ApplicationContext private val context: Context) :
    PersonaPreferencesRepository {

    private object Keys {
        val SELECTED_PERSONA_ID = stringPreferencesKey("selected_persona_id")
    }

    override suspend fun saveSelectedPersonaId(personaId: String?) {
        context.personaDataStore.edit { prefs ->
            if (personaId == null) {
                prefs.remove(Keys.SELECTED_PERSONA_ID)
            } else {
                prefs[Keys.SELECTED_PERSONA_ID] = personaId
            }
        }
    }

    override suspend fun getSelectedPersonaId(): String? = context.personaDataStore.data.map {
        it[Keys.SELECTED_PERSONA_ID]
    }.first()
}
