package com.leo.blockapp.dominio.puertos.salida

/** Puerto de salida: la hora actual. En los tests se reemplaza por un reloj falso. */
interface Reloj {
    fun ahora(): Long

    /** Medianoche (hora local) del día que contiene [momento]. */
    fun inicioDelDia(momento: Long): Long
}
