package com.leo.blockapp.adaptadores.entrada.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leo.blockapp.dominio.modelo.ResultadoUsoHoy
import com.leo.blockapp.dominio.modelo.UsoDeApp
import com.leo.blockapp.dominio.puertos.entrada.ObtenerUsoDeHoy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface EstadoApps {
    data object Cargando : EstadoApps
    data object SinPermiso : EstadoApps
    data class Listo(val apps: List<UsoDeApp>) : EstadoApps
}

/** Adaptador de entrada: solo conoce el puerto ObtenerUsoDeHoy, nunca a Android ni a los adaptadores de salida. */
@HiltViewModel
class AppsViewModel @Inject constructor(
    private val obtenerUsoDeHoy: ObtenerUsoDeHoy,
) : ViewModel() {

    private val _estado = MutableStateFlow<EstadoApps>(EstadoApps.Cargando)
    val estado: StateFlow<EstadoApps> = _estado.asStateFlow()

    fun refrescar() {
        viewModelScope.launch {
            _estado.value = when (val resultado = obtenerUsoDeHoy()) {
                ResultadoUsoHoy.SinPermiso -> EstadoApps.SinPermiso
                is ResultadoUsoHoy.Listo -> EstadoApps.Listo(resultado.apps)
            }
        }
    }
}
