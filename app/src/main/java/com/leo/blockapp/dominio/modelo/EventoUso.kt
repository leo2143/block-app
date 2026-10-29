package com.leo.blockapp.dominio.modelo

/** Una actividad de una app pasó al frente o al fondo de la pantalla en [momento] (ms de reloj). */
data class EventoUso(
    val paquete: String,
    val actividad: String,
    val tipo: TipoEvento,
    val momento: Long,
)

enum class TipoEvento { AL_FRENTE, AL_FONDO }
