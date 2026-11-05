package app.assistant.ui.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.R
import app.assistant.security.lock.LockState
import app.assistant.ui.lock.LockScreen
import app.assistant.ui.navigation.AppNavHost
import app.assistant.ui.startup.CannotUnlockScreen

/**
 * Root composable. The navigation host, and with it every data ViewModel, exists only while
 * the App_Lock is unlocked, so no user data is loaded into UI state while locked (design: App_Lock).
 */
@Composable
fun AssistantApp(viewModel: AppViewModel, startRoute: String?, onUnlock: () -> Unit, onOpenSecuritySettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        AppUiState.Starting -> Loading()
        AppUiState.NeedsConsent -> {
            val consentVm: app.assistant.ui.consent.ConsentViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
            app.assistant.ui.consent.ConsentScreen(onAccept = {
                consentVm.accept()
                viewModel.onConsentAccepted()
            })
        }
        is AppUiState.CannotUnlock -> CannotUnlockScreen(reason = s.reason, onDeleteEverything = viewModel::deleteEverything)
        is AppUiState.Ready -> when (s.lock) {
            LockState.UNLOCKED -> AppNavHost(onDeleteEverything = viewModel::deleteEverything, startRoute = startRoute)
            else -> LockScreen(state = s.lock, onUnlock = onUnlock, onOpenSecuritySettings = onOpenSecuritySettings)
        }
    }
}

@Composable
private fun Loading() {
    val label = stringResource(R.string.loading)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.semantics { contentDescription = label })
    }
}
