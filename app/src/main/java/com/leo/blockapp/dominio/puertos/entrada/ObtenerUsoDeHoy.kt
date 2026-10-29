package com.leo.blockapp.dominio.puertos.entrada

import com.leo.blockapp.dominio.modelo.ResultadoUsoHoy

/** Puerto de entrada: lo que un adaptador (la UI) le puede pedir al dominio. */
interface ObtenerUsoDeHoy {
    suspend operator fun invoke(): ResultadoUsoHoy
}
