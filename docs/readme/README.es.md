# Hovq 0.5.5

[Русский](../../README.md) · [English](README.en.md) · [Deutsch](README.de.md) · [Français](README.fr.md) · [Italiano](README.it.md) · **Español** · [日本語](README.ja.md) · [한국어](README.ko.md) · [简体中文](README.zh.md) · [Қазақша](README.kk.md) · [Татарча](README.tt.md)

Hovq es una aplicación para Android que permite instalar y administrar [Horizon](https://github.com/Thysrael/Horizon) en tu propio VPS y leer resúmenes de noticias. Horizon recopila artículos en el servidor, elimina duplicados y usa el modelo de IA elegido para evaluarlos, traducirlos y preparar cada edición.

[Descargar el APK firmado y ver las notas de la versión](https://github.com/DEGSOL/HorizonManager/releases/latest)

## Funciones

- Fuentes RSS, Telegram, Reddit y Hacker News, perfiles de evaluación, imágenes de portada y ampliación de artículos a petición.
- Ejecución manual, por días de la semana o a intervalos fijos desde una hora elegida. La programación se ejecuta en el VPS.
- Feed con búsqueda, filtros y artículos sin leer, favoritos, archivo y ediciones descargadas para leer sin conexión.
- Registro de actividad y copias de seguridad cifradas de los ajustes del teléfono y del servidor.

## Instalación y primer resumen

1. Instala el APK en Android 8.0 o posterior. Prepara un VPS con Ubuntu Server 24.04 LTS o Debian 12, systemd, SSH y acceso a los repositorios del sistema, GitHub y PyPI. El instalador comprueba la arquitectura, un mínimo de 432 MiB de RAM y 1,5 GiB de espacio libre. La recopilación y el procesamiento pueden requerir más recursos.
2. Añade el servidor en Hovq, introduce la contraseña SSH o elige una clave privada y verifica la huella en el panel del proveedor. SSH debe permitir el reenvío a `127.0.0.1:18777` en el VPS. Confirma la instalación e indica la contraseña de sudo si se solicita. También puedes conectar un agente existente sin reinstalarlo.
3. Configura el proveedor de IA, el modelo y la clave API. Para OpenRouter y UnoRouter puedes cargar el catálogo de modelos en la aplicación. Añade una fuente, elige un perfil de procesamiento e inicia la recopilación.
4. Abre el resumen en el feed. Elige la programación en los ajustes de noticias y el idioma del resumen en los ajustes de IA. La interfaz y los resúmenes admiten todos los idiomas enumerados arriba.

## Notificaciones y actualizaciones

Activa las notificaciones de nuevos resúmenes en **Ajustes → Interfaz → Notificaciones** y autorízalas en Android. Hovq se conecta brevemente al VPS por SSH o HTTPS cerca de las horas de ejecución programadas y durante la preparación. La notificación aparece cuando hay un resumen listo. No se ejecuta un servicio permanente. Android puede aplazar las comprobaciones en segundo plano, especialmente durante el reposo profundo.

El APK se instala sobre Hovq 0.4.5–0.5.4 con el mismo certificado. Las versiones 0.4.4 y anteriores requieren una instalación limpia. En un VPS ya configurado, actualiza el agente a 0.1.17 desde Hovq para consultar la programación y el estado del resumen en una sola solicitud.

## Compilar desde el código fuente

Usa JDK 17 o 21, Android SDK 36 y Build Tools 36.0.0. Indica la ruta del SDK mediante `ANDROID_HOME` o `android/local.properties`.

```bash
cd android
./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
```

Para generar un APK firmado, configura

`HM_RELEASE_KEYSTORE`, `HM_RELEASE_KEY_ALIAS`, `HM_RELEASE_STORE_PASSWORD`, `HM_RELEASE_KEY_PASSWORD`

[Código de la aplicación y del agente de esta versión](https://github.com/DEGSOL/HorizonManager/tree/v0.5.5) · [Arquitectura y API](https://github.com/DEGSOL/HorizonManager/blob/v0.5.5/docs/ARCHITECTURE.md) · [Pruebas](https://github.com/DEGSOL/HorizonManager/blob/v0.5.5/docs/TESTING.md)
