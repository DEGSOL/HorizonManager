# Hovq 0.5.5

[Русский](../../README.md) · [English](README.en.md) · [Deutsch](README.de.md) · [Français](README.fr.md) · **Italiano** · [Español](README.es.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [简体中文](README.zh.md) · [Қазақша](README.kk.md) · [Татарча](README.tt.md)

Hovq è un’app Android per installare e gestire [Horizon](https://github.com/Thysrael/Horizon) sul proprio VPS e leggere rassegne di notizie. Horizon raccoglie gli articoli sul server, elimina i duplicati e usa il modello di IA scelto per valutarli, tradurli e preparare ogni rassegna.

[Scarica l’APK firmato e leggi le note di rilascio](https://github.com/DEGSOL/HorizonManager/releases/latest)

## Funzionalità

- Fonti RSS, Telegram, Reddit e Hacker News, profili di valutazione, immagini di copertina e arricchimento su richiesta.
- Avvio manuale, per giorni della settimana o a intervalli regolari con orario iniziale configurabile. La pianificazione viene eseguita sul VPS.
- Feed con ricerca, filtri e articoli non letti, preferiti, archivio e rassegne scaricate per la lettura offline.
- Registro delle attività e backup cifrati delle impostazioni del telefono e del server.

## Installazione e prima rassegna

1. Installa l’APK su Android 8.0 o successivo. Prepara un VPS con Ubuntu Server 24.04 LTS o Debian 12, systemd, SSH e accesso ai repository del sistema operativo, GitHub e PyPI. L’installatore verifica l’architettura, almeno 432 MiB di RAM e 1,5 GiB di spazio libero. La raccolta e l’elaborazione possono richiedere più risorse.
2. Aggiungi il server in Hovq, inserisci la password SSH o seleziona una chiave privata e verifica l’impronta nella console del fornitore. SSH deve consentire l’inoltro verso `127.0.0.1:18777` sul VPS. Conferma l’installazione e immetti la password sudo se richiesta. Puoi anche collegarti a un agente esistente senza reinstallarlo.
3. Imposta il fornitore di IA, il modello e la chiave API. Per OpenRouter e UnoRouter puoi caricare il catalogo dei modelli nell’app. Aggiungi una fonte, scegli un profilo di elaborazione e avvia la raccolta.
4. Apri la rassegna nel feed. Scegli la pianificazione nelle impostazioni delle notizie e la lingua della rassegna nelle impostazioni IA. L’interfaccia e le rassegne supportano tutte le lingue elencate sopra.

## Notifiche e aggiornamenti

Attiva le notifiche per le nuove rassegne in **Impostazioni → Interfaccia → Notifiche** e concedi il permesso in Android. Hovq si collega brevemente al VPS via SSH o HTTPS vicino agli avvii pianificati e durante la preparazione. La notifica appare quando una rassegna è pronta. Non viene eseguito un servizio permanente. Android può posticipare i controlli in background, soprattutto in sospensione profonda.

L’APK si installa sopra Hovq 0.4.5–0.5.4 se il certificato coincide. Le versioni 0.4.4 e precedenti richiedono un’installazione pulita. Su un VPS già configurato, aggiorna l’agente alla versione 0.1.17 da Hovq per controllare pianificazione e stato della rassegna con una sola richiesta.

## Compilazione dal codice sorgente

Usa JDK 17 o 21, Android SDK 36 e Build Tools 36.0.0. Indica il percorso dell’SDK con `ANDROID_HOME` o `android/local.properties`.

```bash
cd android
./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
```

Per ottenere un APK firmato, imposta

`HM_RELEASE_KEYSTORE`, `HM_RELEASE_KEY_ALIAS`, `HM_RELEASE_STORE_PASSWORD`, `HM_RELEASE_KEY_PASSWORD`

[Sorgenti dell’app e dell’agente per questa versione](https://github.com/DEGSOL/HorizonManager/tree/v0.5.5) · [Architettura e API](https://github.com/DEGSOL/HorizonManager/blob/v0.5.5/docs/ARCHITECTURE.md) · [Test](https://github.com/DEGSOL/HorizonManager/blob/v0.5.5/docs/TESTING.md)
