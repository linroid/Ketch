<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="art/icon-white.svg">
    <source media="(prefers-color-scheme: light)" srcset="art/icon-app.svg">
    <img alt="Ketch" src="art/icon-app.svg" height="128">
  </picture>
</p>

<h1 align="center">Ketch</h1>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.zh-CN.md">简体中文</a> ·
  <a href="README.es.md">Español</a> ·
  <a href="README.ja.md">日本語</a> ·
  <a href="README.de.md">Deutsch</a> ·
  <b>Français</b>
</p>

<p align="center">
  <b>Un gestionnaire de téléchargements rapide et open source pour tous vos appareils.</b><br>
  macOS · Windows · Linux · Android · iOS · Web · Ligne de commande<br>
  Une bibliothèque Kotlin Multiplatform à intégrer dans votre propre application.
</p>

<p align="center">

[![Dernière version](https://img.shields.io/github/v/release/linroid/Ketch?include_prereleases&filter=v*&label=Download&logo=github)](https://github.com/linroid/Ketch/releases/latest)
[![Maven Central](https://img.shields.io/maven-central/v/com.linroid.ketch/core?label=Maven%20Central&logo=apache-maven&logoColor=white)](https://central.sonatype.com/namespace/com.linroid.ketch)
[![Application web](https://img.shields.io/badge/Web_app-open-4F5DE4.svg?logo=webassembly&logoColor=white)](https://linroid.com/Ketch/)
[![Android](https://img.shields.io/badge/Android-8.0+-3DDC84.svg?logo=android&logoColor=white)](https://github.com/linroid/Ketch/releases/latest)
[![iOS](https://img.shields.io/badge/iOS-18+-000000.svg?logo=apple&logoColor=white)](app/ios/)
[![Ordinateur](https://img.shields.io/badge/Desktop-macOS_|_Windows_|_Linux-DB380E.svg)](https://github.com/linroid/Ketch/releases/latest)
[![Licence](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

</p>

<p align="center">
  <a href="#download"><b>Télécharger</b></a> ·
  <a href="https://youtu.be/l8RCUYQfRrc"><b>Voir la démo</b></a> ·
  <a href="#features"><b>Fonctionnalités</b></a> ·
  <a href="#getting-started"><b>Premiers pas</b></a> ·
  <a href="docs/developers.md"><b>Pour les développeurs</b></a>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="art/showcase-dark.png">
    <source media="(prefers-color-scheme: light)" srcset="art/showcase-light.png">
    <img
      alt="Ketch sur ordinateur, Android, iOS et en ligne de commande : tableau des téléchargements avec les connexions en temps réel, appareils sur Android, connexions sur iOS et ketch dans un terminal"
      src="art/showcase-light.png" width="100%">
  </picture>
</p>

<p align="center">
  <a href="https://youtu.be/l8RCUYQfRrc"><b>▶ Voir Ketch en action</b></a>
</p>

Ketch répartit chaque téléchargement entre plusieurs connexions parallèles et affiche chacune en
temps réel. Liens web, serveurs FTP, torrents et liens magnet se retrouvent au même endroit.
Appairez votre ordinateur portable, votre téléphone et votre serveur domestique pour suivre et
contrôler leurs téléchargements depuis n'importe lequel de ces appareils.

**Vous développez votre propre application ?** Intégrez le moteur qui anime les applications Ketch,
avec téléchargements parallèles, pause et reprise, files d'attente et limites de vitesse.
La bibliothèque Kotlin Multiplatform est disponible sur Maven Central.
[Commencer à intégrer Ketch →](docs/developers.md#quick-start)

> [!WARNING]
> 🚧 Ketch est en cours de développement et ne propose pour l'instant que des versions candidates.
> Des problèmes peuvent subsister ; merci de [nous les
> signaler](https://github.com/linroid/Ketch/issues).

<a id="download"></a>

## Télécharger

| Plateforme | Où le trouver |
|---|---|
| macOS (Apple silicon, Intel) | `.dmg` de la [dernière version][release] |
| Windows (x64, ARM64) | `.msi` ou `.zip` portable sans installation de la [dernière version][release] ([à propos de la version portable](docs/updates.md#the-portable-windows-app)) |
| Linux (x64, ARM64) | `.deb` de la [dernière version][release] |
| Android 8.0+ | `.apk` de la [dernière version][release] |
| iOS 18+ | Compiler depuis les sources avec Xcode ([`app/ios`](app/ios/)) |
| Web | [linroid.com/Ketch](https://linroid.com/Ketch/), pour contrôler Ketch sur un autre appareil |
| Extension de navigateur | Chrome, Edge, Brave, Firefox et autres : `.zip` de la [dernière version][release] ([installation](app/browser-extension/README.md#installing)) |
| Ligne de commande et serveur | macOS, Linux et Windows : [script d'installation](#run-ketch-on-a-server) ou [dernière version][release] |

Les applications de bureau incluent leur environnement d'exécution et la ligne de commande est un
unique binaire natif : aucune n'a besoin de Java. Toutes deux se mettent à jour depuis les versions
publiées ici : l'application via Réglages → À propos, la ligne de commande avec `ketch update`
([fonctionnement des mises à jour](docs/updates.md)).

[release]: https://github.com/linroid/Ketch/releases/latest

<a id="features"></a>

## Fonctionnalités

### Des téléchargements plus rapides

- **Connexions parallèles** — Ketch découpe un fichier en plages et les télécharge simultanément.
  L'onglet Connexions affiche chacune sur une ligne avec sa propre vitesse. Vous pouvez ajouter ou
  retirer des connexions pendant le téléchargement.
- **Plusieurs réseaux à la fois** — Répartissez un téléchargement entre Wi-Fi, Ethernet et, sur
  Android, données mobiles ([fonctionnement](docs/multiple-networks.md)).
- **Proxy** — Téléchargez via le proxy du système, un proxy HTTP ou SOCKS5 avec nom d'utilisateur
  et mot de passe, ou sans proxy, en joignant directement les hôtes de votre choix ; aussi pour un
  seul téléchargement ([détails](docs/proxy.md)).
- **Pause et reprise, même après un redémarrage** — Avant de reprendre, Ketch vérifie que le fichier
  sur le serveur n'a pas changé et relance automatiquement les connexions en échec.

### Tous les types de liens

- **HTTP et HTTPS**, avec les cookies et le référent de la page lorsque l'extension de navigateur
  transmet le téléchargement.
- **FTP et FTPS**, avec connexions parallèles et reprise.
- **BitTorrent et liens magnet** — Torrents v1, v2 et hybrides, avec sélection des fichiers, dans
  un moteur entièrement écrit en Kotlin ([détails](docs/torrent.md)).
- **HLS (`.m3u8`) et DASH (`.mpd`)** — Enregistrez les flux de durée finie non chiffrés dans un
  seul fichier multimédia. Les listes maîtresses HLS utilisent la variante au débit le plus élevé
  ([prise en charge et limites](docs/media.md)).
- Ketch peut ouvrir les liens magnet et les fichiers `.torrent` du système : un clic dans le
  navigateur ou le gestionnaire de fichiers les envoie à Ketch.

### Tous vos appareils dans une application

- **Appairage par QR code** — Sur l'appareil à partager, ouvrez **Réglages → Partage** et choisissez
  **Autoriser un autre appareil**. Scannez le code avec votre téléphone ou copiez le lien
  d'appairage. Vous pouvez aussi choisir l'appareil dans **Rechercher sur le réseau** et autoriser
  la connexion lorsque les deux affichent les mêmes quatre chiffres.
- **Chaque appareil dans la barre latérale**, avec sa vitesse et son état en temps réel. Passez
  de l'un à l'autre avec un raccourci ou ouvrez **Tous les appareils** pour réunir les
  téléchargements dans un seul tableau.
- **Envoyez les téléchargements au bon appareil** — Ajoutez un lien à n'importe quel appareil,
  déposez-le sur un appareil dans la barre latérale, ou envoyez ou déplacez un téléchargement vers
  un autre appareil sans changer de vue.
- **Sans interface graphique sur NAS ou serveur** — `ketch server` exécute le même moteur avec
  une API REST et l'application web intégrées. Les applications le trouvent sur votre réseau.
- **Depuis le terminal** — `ketch add`, `list`, `pause`, `resume` et `watch` pilotent les
  téléchargements de l'application Ketch ou d'un serveur depuis un terminal ou un script ; `watch`
  produit des lignes JSON ([CLI](cli/README.md#work-on-a-running-ketch)).

### Maîtrisez votre bande passante

- **Modes de vitesse** — Pleine vitesse, **Vitesse réduite** pour laisser de la place à un appel
  vidéo, ou **Automatique**, qui alterne selon un programme hebdomadaire.
- **Réglages par téléchargement** — Modifiez les limites de vitesse, le nombre de connexions et
  les priorités pendant le téléchargement. **Urgente** met en pause un téléchargement moins
  important pour démarrer immédiatement.
- **Une file d'attente souple** — Limitez les téléchargements simultanés au total et par site,
  ou choisissez une heure de démarrage ultérieure.

### Adapté à votre façon de travailler

- **Extension de navigateur** pour Chrome, Edge, Brave, Firefox et autres : elle prend en charge
  les téléchargements et liens magnet, et ajoute **Télécharger avec Ketch** au menu contextuel,
  pour cet ordinateur ou un autre appareil ([détails](app/browser-extension/README.md)).
- **Collez pour télécharger** — Collez un lien pour l'ajouter, avec possibilité d'annuler,
  ou laissez Ketch proposer les liens que vous copiez.
- **Au clavier sur ordinateur** — `⌘K` (`Ctrl+K` sous Windows et Linux) ouvre la palette de
  commandes, et la liste des raccourcis présente toutes les combinaisons.
- **Dossiers par catégorie** — Vidéos, musique, documents et archives vont dans leurs propres
  dossiers au sein du dossier de téléchargement, selon le type de fichier ou le site web :
  partez des suggestions dans **Réglages → Téléchargements** ou définissez vos règles.
- **Prêt à ouvrir dès la fin** — Ouvrez le fichier, affichez-le dans son dossier ou faites-le
  glisser hors de la liste, directement depuis celle-ci.
- **Intégré à chaque plateforme** — Barre de menus ou zone de notification, notifications et
  progression dans le Dock et la barre des tâches sur ordinateur ; téléchargements en arrière-plan
  sur Android et iOS 26.
- **Éveillé pendant les téléchargements** — Les apps pour ordinateur et Android empêchent la mise
  en veille automatique du système pendant les téléchargements ; l'écran peut tout de même
  s'éteindre (**Réglages → Général**).
- **Votre style, votre langue** — Thèmes clair et sombre avec quatre couleurs d'accent, en English,
  简体中文, 繁體中文, 日本語, 한국어, Español, Português (Brasil), Deutsch et Français
  ([traductions](docs/development/localization.md)).

### Trouvez des téléchargements avec l'IA (aperçu)

- **Découvrir** — Décrivez ce que vous cherchez, par exemple « la dernière ISO d'Ubuntu Server ».
  Un agent IA recherche sur le web, vérifie les liens et classe les téléchargements trouvés.
  **Trouver une autre source** fait de même lorsqu'un téléchargement échoue. Affinez la recherche
  avec des messages supplémentaires, écartez les résultats inutiles et reprenez vos recherches
  depuis l'historique. Découvrir demande votre autorisation avant d'ouvrir un site, sauf si vous
  l'avez déjà accordée. Utilisez votre propre service de modèles : OpenAI, Anthropic, Gemini,
  Ollama ou tout service compatible avec OpenAI. Disponible dans les applications de bureau et
  Android, ainsi qu'avec `ketch ai-discover` ([configuration](docs/ai-discovery.md)).
- **Serveur MCP** — `ketch mcp` permet aux assistants IA de lancer, suivre et gérer les
  téléchargements de l'application Ketch ou d'un serveur via le
  [Model Context Protocol](cli/README.md#mcp-server).

<a id="getting-started"></a>

## Premiers pas

1. Installez Ketch depuis [Télécharger](#download), puis ouvrez-le. Sur téléphone, quelques écrans
   d'accueil vous demandent où enregistrer les téléchargements.
2. Ajoutez un téléchargement : collez un lien (`⌘V` ou `Ctrl+V`), déposez un lien ou un fichier
   `.torrent` sur la fenêtre, ou choisissez **Ajouter**.
3. Pour contrôler l'ordinateur depuis votre téléphone, ouvrez **Réglages → Partage**, choisissez
   **Autoriser un autre appareil** et scannez le QR code avec l'appareil photo du téléphone.
4. Installez l'[extension de navigateur](app/browser-extension/README.md) pour transmettre les
   téléchargements du navigateur à Ketch.

<a id="run-ketch-on-a-server"></a>

### Exécuter Ketch sur un serveur

Installez l'outil en ligne de commande sur macOS ou Linux (sous Windows, prenez le `.zip` de la
[dernière version][release]) :

```bash
curl -fsSL https://raw.githubusercontent.com/linroid/Ketch/main/install.sh | bash
```

Démarrez le serveur, avec son API REST et son application web sur le port 8642 :

```bash
ketch server
```

Ajoutez-le ensuite dans les applications via **Appareils → Ajouter un appareil**, ou ouvrez
`http://<adresse du serveur>:8642` dans un navigateur. La ligne de commande peut aussi télécharger
seule :

```bash
ketch https://example.com/file.zip
```

Définissez le code d'accès, le port et les autres options dans un
[fichier de configuration](cli/README.md#configuration-file).
La [documentation CLI](cli/README.md) présente toutes les commandes.

## Feuille de route

- **Distribution avant v1.0.0** — Versions Windows signées, versions macOS signées et notariées,
  extensions dans les boutiques de navigateurs et parcours d'installation et de mise à jour vérifiés
  ([plan](docs/plans/v1-distribution.md))
- **Metalink** — Téléchargements depuis plusieurs miroirs à la fois, avec sommes de contrôle
- **WebDAV** — Téléchargements depuis des serveurs WebDAV, avec reprise
- **Davantage de formats HLS et DASH** — Choix de la qualité, fusion des pistes audio et vidéo
  séparées et prise en charge du chiffrement HLS AES-128 ; les téléchargements d'un seul flux de
  durée finie non chiffré sont déjà pris en charge
- **Extraction multimédia** — Enregistrer les médias des pages web
- **Détection de ressources** — Trouver les fichiers téléchargeables d'une page web
- **Sommes de contrôle** — Vérifier un téléchargement avec une empreinte fournie par vous ou
  publiée par le serveur
- **Téléchargements segmentés plus rapides** — Les connexions terminées reprennent ce qui reste
  aux plus lentes pour ne plus attendre la connexion la plus lente
- **Détection des blocages et réglages de reprise** — Reconnecter lorsqu'une connexion n'envoie
  plus de données et choisir les délais d'attente et le nombre de tentatives, même illimité
- **Distinguer les fichiers incomplets** — Écrire sous un nom temporaire et attribuer le nom
  définitif une fois le téléchargement terminé
- **Choix des fichiers torrent à tout moment** — Choisir les fichiers d'un lien magnet une fois
  ses détails reçus et modifier la sélection pendant le téléchargement
- **À la fin des téléchargements** — Quitter Ketch, mettre l'ordinateur en veille ou l'éteindre,
  au choix, une fois la file d'attente vide
- **Automatisation** — Exécuter une commande ou appeler un webhook à la fin ou à l'échec d'un
  téléchargement
- **Image Docker** — Une image officielle pour NAS et serveurs domestiques x64 et ARM, avec
  contrôle d'état et port torrent fixe
- **Transferts entre appareils** — Envoyer vers et Déplacer vers transmettent les données déjà
  téléchargées pour que l'autre appareil reprenne au lieu de recommencer
  ([plan](docs/plans/task-transfer.md))
- **Appareils auxiliaires** — D'autres appareils téléchargent des parties du même fichier avec
  leur propre connexion et peuvent rejoindre ou quitter le téléchargement en cours
  ([proposition](docs/design/multi-instance-downloads.md))

## Pour les développeurs

**Intégrez Ketch dans votre application.** Sa bibliothèque Kotlin Multiplatform fournit le même
moteur que les applications Ketch, avec votre propre interface et logique métier. Ajoutez les
modules nécessaires depuis Maven Central sous `com.linroid.ketch`.

Exécutez le moteur dans votre application Android, iOS ou JVM avec `core` et `ktor`. Node.js et
WASI peuvent utiliser `core` avec un moteur HTTP personnalisé. Avec `remote`, contrôlez un serveur
Ketch depuis Android, iOS, la JVM ou le navigateur, via la même `KetchApi`.

- [Guide de développement](docs/developers.md) — Modules, démarrage rapide, API REST et extensions
- [Référence de l'API](docs/api.md) — Installation, configuration, priorités, erreurs et
  journalisation
- [Architecture](docs/architecture.md) — Chaîne de téléchargement et structure des applications

## Documentation

Les documents détaillés ci-dessous sont en anglais.

- [Ligne de commande](cli/README.md) — Téléchargements, serveur, MCP, découverte par IA et
  configuration
- [Extension de navigateur](app/browser-extension/README.md) — Installation, capture, autorisations
  et confidentialité
- [BitTorrent](docs/torrent.md) — Prise en charge et limites des torrents et liens magnet
- [Plusieurs réseaux](docs/multiple-networks.md) — Répartir les téléchargements entre interfaces
  réseau
- [Découverte par IA](docs/ai-discovery.md) — Fournisseurs, clés, recherche web, accès aux pages,
  conversations et historique
- [Journalisation](docs/logging.md) — Emplacement des journaux et pièces à joindre à un rapport de
  bug

## Contribuer

Les contributions sont les bienvenues ! Ouvrez une issue pour discuter de votre idée avant un PR.
Consultez les [règles de style](docs/development/code-style.md),
les [règles de test](docs/development/testing.md) et le
[guide de localisation](docs/development/localization.md). Les traductions se proposent aussi par PR.

Les agents de programmation doivent commencer par [AGENTS.md](AGENTS.md), les instructions communes
à tous les agents et éditeurs. Si votre outil ne le charge pas automatiquement, demandez-lui de lire
`AGENTS.md` et les règles liées avant toute modification. Gardez les instructions communes dans ces
fichiers ; les fichiers propres à chaque outil doivent uniquement y faire référence.

## Licence

Apache-2.0
