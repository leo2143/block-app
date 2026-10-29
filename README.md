# Block App

App Android gratuita, para uso personal, que bloquea las apps que elijas (Instagram, X, etc.) cuando superas el tiempo de uso que definiste. Al llegar al límite empieza un enfriamiento de 1 hora o más; al terminar se desbloquea y el ciclo vuelve a empezar.

## Estado

Fase 1 del MVP: la app muestra cuántos minutos usaste hoy cada app instalada. Todavía no bloquea nada.

| Fase | Contenido | Estado |
| --- | --- | --- |
| 1 | Medir el uso de hoy por app | Hecha |
| 2 | Configurar bloqueos (apps, minutos, enfriamiento) | Pendiente |
| 3 | Bloquear con el servicio de accesibilidad | Pendiente |
| 4 | Grupos de apps y avisos | Pendiente |

## Cómo abrirlo

1. Instala [Android Studio](https://developer.android.com/studio) (versión reciente).
2. `File > Open` y elige esta carpeta. Android Studio descarga Gradle y las dependencias.
3. Conecta tu teléfono con la depuración USB activada y pulsa **Run**.
4. La primera vez, la app te pide el permiso de **Acceso de uso**: busca Block App en la lista y actívalo.

Requiere Android 10 o superior.

## Tests

Los tests del dominio corren en el computador, sin teléfono:

```bash
./gradlew test
```

## Arquitectura

Hexagonal (puertos y adaptadores), en un solo módulo, con Hilt para la inyección de dependencias.

```text
app/src/main/java/com/leo/blockapp/
├── dominio/                  el hexágono: Kotlin puro, sin Android
│   ├── modelo/               EventoUso, AppInstalada, UsoDeApp
│   ├── uso/                  CalculadoraUso (eventos → minutos por app)
│   ├── puertos/
│   │   ├── entrada/          ObtenerUsoDeHoy
│   │   └── salida/           FuenteEventosUso, CatalogoApps, PermisosSistema, Reloj
│   └── casos/                ObtenerUsoDeHoyCaso
├── adaptadores/
│   ├── entrada/ui/           Compose + ViewModel
│   └── salida/               UsageStatsManager, PackageManager, AppOps, reloj del sistema
└── di/                       módulos de Hilt que conectan puertos con adaptadores
```

Reglas:

1. Nada en `dominio/` importa Android, AndroidX ni Hilt (lo verifica `ArquitecturaTest`).
2. Los adaptadores conocen los puertos; el dominio nunca conoce a los adaptadores.
3. Un adaptador de entrada solo usa puertos de entrada.
4. Solo los módulos de `di/` conectan adaptadores con puertos.
5. La hora siempre viene del puerto `Reloj`.
