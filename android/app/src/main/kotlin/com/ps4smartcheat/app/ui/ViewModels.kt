package com.ps4smartcheat.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ps4smartcheat.app.SmartCheatApp
import com.ps4smartcheat.app.data.AppContainer

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as SmartCheatApp).container

/** Creates a ViewModel bound to the application's [AppContainer]. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = appContainer()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}
