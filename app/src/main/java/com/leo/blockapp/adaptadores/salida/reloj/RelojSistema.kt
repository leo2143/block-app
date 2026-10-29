package com.leo.blockapp.adaptadores.salida.reloj

import com.leo.blockapp.dominio.puertos.salida.Reloj
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

class RelojSistema @Inject constructor() : Reloj {

    override fun ahora(): Long = System.currentTimeMillis()

    override fun inicioDelDia(momento: Long): Long {
        val zona = ZoneId.systemDefault()
        return Instant.ofEpochMilli(momento).atZone(zona).toLocalDate()
            .atStartOfDay(zona).toInstant().toEpochMilli()
    }
}
