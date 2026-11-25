# Hovq 0.5.8

[Русский](../../README.md) · [English](README.en.md) · [Deutsch](README.de.md) · **Français** · [Italiano](README.it.md) · [Español](README.es.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [简体中文](README.zh.md) · [Қазақша](README.kk.md) · [Татарча](README.tt.md)

Hovq est une application Android pour installer et gérer [Horizon](https://github.com/Thysrael/Horizon) sur votre propre VPS et lire des bulletins d’actualité. Horizon collecte les articles sur le serveur, élimine les doublons et utilise le modèle d’IA choisi pour évaluer et traduire les articles et préparer le bulletin.

[Télécharger l’APK signé et consulter les notes de version](https://github.com/DEGSOL/HorizonManager/releases/latest)

## Fonctionnalités

- Sources RSS, Telegram, Reddit et Hacker News, profils d’évaluation, images de couverture et enrichissement à la demande.
- Lancement manuel, par jour de la semaine ou à intervalles réguliers avec une heure de départ configurable. La planification s’exécute sur le VPS.
- Fil avec recherche, filtres et articles non lus, favoris, archives et bulletins téléchargés pour la lecture hors ligne.
- Journal d’activité et sauvegardes chiffrées des réglages du téléphone et du serveur.

## Installation et premier bulletin

**Pour l’installation, il suffit d’un VPS sous Ubuntu Server 24.04 LTS ou Debian 12 avec un accès SSH.** Connectez-vous depuis Hovq puis confirmez. L’application installe automatiquement Horizon, son agent et les dépendances nécessaires.

1. Installez l’APK sous Android 8.0 ou version ultérieure. Préparez un VPS sous Ubuntu Server 24.04 LTS ou Debian 12 avec systemd, SSH et accès aux dépôts du système, à GitHub et à PyPI. L’installateur vérifie l’architecture, un minimum de 432 Mio de RAM et 1,5 Gio d’espace libre. La collecte et le traitement peuvent exiger davantage de ressources.
2. Ajoutez le serveur dans Hovq, saisissez un mot de passe SSH ou sélectionnez une clé privée, puis vérifiez l’empreinte dans la console de votre fournisseur. SSH doit autoriser la redirection vers `127.0.0.1:18777` sur le VPS. Confirmez l’installation et saisissez le mot de passe sudo si nécessaire. Vous pouvez aussi connecter un agent existant sans le réinstaller.
3. Choisissez le fournisseur d’IA et le modèle, puis enregistrez la clé API. L’application peut charger le catalogue de modèles OpenRouter ou UnoRouter. Ajoutez une source, choisissez un profil et lancez la collecte.
4. Ouvrez le bulletin dans le fil. Les réglages d’actualité permettent de choisir la planification et les réglages d’IA, la langue du bulletin. L’interface et les bulletins prennent en charge toutes les langues indiquées ci-dessus.

## Notifications et mise à jour

Activez les notifications dans **Paramètres → Interface → Notifications** et autorisez-les dans Android. Hovq maintient une connexion directe au VPS par SSH ou par HTTPS configuré. Pendant la surveillance, Android affiche une notification de service silencieuse. Une autre notification signale qu’un bulletin est prêt. Si la connexion est interrompue, Hovq se reconnecte et vérifie si un nouveau bulletin est disponible.

L’APK s’installe par-dessus Hovq 0.4.5–0.5.7 signé avec le même certificat. Les versions 0.4.4 et antérieures nécessitent une installation propre. Sur un VPS déjà configuré, mettez à jour l’agent vers la version 0.1.20 depuis Hovq. Il repère les articles qui décrivent le même événement avec des formulations différentes, y compris avec l’enrichissement à la demande. Le réglage de dédoublonnage de chaque profil reste applicable.

## Compiler depuis les sources

Utilisez le JDK 17 ou 21, le SDK Android 36 et les Build Tools 36.0.0. Indiquez le chemin du SDK avec `ANDROID_HOME` ou `android/local.properties`.

```bash
cd android
./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
```

Pour produire un APK signé, configurez

`HM_RELEASE_KEYSTORE`, `HM_RELEASE_KEY_ALIAS`, `HM_RELEASE_STORE_PASSWORD`, `HM_RELEASE_KEY_PASSWORD`

[Code de l’application et de l’agent pour cette version](https://github.com/DEGSOL/HorizonManager/tree/v0.5.8) · [Architecture et API](https://github.com/DEGSOL/HorizonManager/blob/v0.5.8/docs/ARCHITECTURE.md) · [Tests](https://github.com/DEGSOL/HorizonManager/blob/v0.5.8/docs/TESTING.md)
