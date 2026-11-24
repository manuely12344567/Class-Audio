# ClasseAudio — hôte Windows local et clients Android

**État : la release v1.0.0 reste la dernière release. L'itération design/découverte est en cours de validation; l'APK de prévisualisation a été compilé, mais n'a pas encore été testé sur téléphone. Aucune nouvelle release ou tag ne sera créé avant validation sur appareils.** PocketPad est un autre projet et n'a pas été modifié.

## But de cette première étape

Le PC Windows de la salle lance un relais LiveKit local et affiche son état. Le serveur reste dans le réseau de la classe; aucune connexion à LiveKit Cloud ou à un autre serveur permanent n'est prévue. Les applications Windows et Android partagent désormais une identité visuelle au mégaphone. Côté Android, le parcours normal demande le code de classe; l'adresse reste cachée dans les options de dépannage.

Le prototype expose déjà une API locale prévue pour ces clients :

- `GET /health` — contrôle local;
- `GET /api/v1/session` — détails de connexion pour l'application Android après découverte;
- `POST /api/v1/join` — échange du code de classe (et du PIN séparé pour le professeur) contre un JWT LiveKit et un jeton de contrôle local;
- `POST /api/v1/speaking/request` — demande de parole d'un élève;
- `GET /api/v1/speaking/requests` — file visible par le professeur;
- `POST /api/v1/speakers/{identity}/allow` et `/revoke` — le serveur n'autorise qu'un élève à publier son microphone à la fois;
- `GET /api/v1/participants` — état des participants pour le tableau de bord professeur.

La publication des élèves est refusée explicitement dans leur JWT, y compris la publication de données. Le PC signe les jetons localement avec une clé générée pour la session; les secrets sont supprimés à l'arrêt normal. Aucun enregistrement audio n'est configuré.

## Découverte et ports prévus

L'hôte annonce le service DNS-SD/mDNS **`_classeaudio._tcp.local`** sur le port TCP `8090`. L'application Android cherche d'abord ce service, puis, sans réponse, balaie de façon bornée les sous-réseaux IPv4 Wi-Fi/Ethernet actifs via `GET /api/v1/session` (jusqu'à trois interfaces, 512 hôtes, 32 requêtes simultanées). Cette approche reprend le balayage par étapes de LocalSend sans implémenter son protocole de transfert de fichiers. Le code de classe n'est envoyé qu'une fois l'hôte trouvé; l'adresse est masquée dans l'interface normale et réservée au dépannage.

| Usage | Port | Protocole |
|---|---:|---|
| Signalisation LiveKit | 7880 | TCP |
| Repli WebRTC | 7881 | TCP |
| Média WebRTC multiplexé | 7882 | UDP |
| API locale / découverte | 8090 | TCP |
| DNS-SD/mDNS | 5353 | UDP multicast |

À l'installation ou au premier démarrage, Windows peut demander l'autorisation du pare-feu. N'autoriser que le profil **Réseau privé** et les ports nécessaires. Ne pas ouvrir ces ports sur Internet. Le PC et les téléphones doivent être sur le même réseau local; l'isolation Wi‑Fi, les VLAN et le filtrage multicast peuvent empêcher la connexion ou la découverte.

Le serveur est configuré avec `7882/UDP` et `7881/TCP` comme candidat de repli, mais cette configuration et sa capacité restent à confirmer sur le point d'accès réel de l'école.

## Artefacts compilés et état de persistance

- L'APK v1 original est disponible dans la [release GitHub v1.0.0](https://github.com/manuely12344567/Class-Audio/releases/tag/v1.0.0). La preview locale `ClasseAudio-Android-preview-debug.apk` contient le nouveau logo et la nouvelle découverte (package `com.classeaudio.app.debug`, version `0.1.0-debug`, label **ClasseAudio**); elle est signée en debug, non destinée au Play Store et pas encore testée sur un téléphone. Selon la signature de l'ancienne app, désinstallez-la si Android refuse la mise à jour. `SHA256SUMS.txt` contient le SHA-256 du binaire LiveKit versionné.
- Le build Windows x64 avait réussi, mais l'EXE généré n'est pas versionné dans le dépôt. Le code source et `runtime/livekit-server.exe` sont conservés; le workflow Windows ci-dessous le recrée et le téléverse comme artefact téléchargeable.
- L'APK preview a passé `:app:assembleDebug` localement avec le SDK Android 35; quelques avertissements de dépréciation Android NSD subsistent. Le build WPF avec `EnableWindowsTargeting=true` avait réussi avant une dernière modification de palette dans `App.xaml`; cette modification n'a pas été recompilée localement et doit être confirmée par le workflow Windows. Ni l'APK preview ni l'EXE n'ont été exécutés depuis cette itération sur un appareil/PC réel; réseau, micro, Bluetooth, écoute et écho restent à valider.

## Builds sur GitHub Actions

Deux workflows sont fournis et peuvent être lancés automatiquement après un `push` ciblé ou manuellement dans l'onglet **Actions** du dépôt :

- `.github/workflows/windows-build.yml` compile l'hôte sous Windows, puis téléverse l'application publiée (dont `ClasseAudio.exe` et `runtime/`) et l'installateur dans l'artefact `ClasseAudio-Windows-x64`.
- `.github/workflows/android-build.yml` compile l'APK debug et le téléverse dans `ClasseAudio-Android-debug`.

Les artefacts sont à télécharger depuis la page du run Actions. Ces builds n'exigent pas de Personal Access Token : GitHub fournit le jeton de workflow aux actions. Un utilisateur ayant accès au dépôt doit toutefois y pousser le code ou lancer le workflow. Ne collez pas de token dans une conversation; utilisez GitHub web, `git push` depuis votre poste ou une intégration sécurisée.

## Compilation Windows

Pré-requis sur une machine Windows x64 :

- .NET 10 SDK;
- Inno Setup 6 pour produire l'installateur.

Dans PowerShell, depuis le dossier du projet :

```powershell
.\scripts\Build-Windows.ps1
```

Le script publie une application autonome dans `publish/` puis, si Inno Setup est installé, crée `dist/ClasseAudio-Setup-x64.exe`. Le binaire LiveKit est déjà fourni dans `runtime/`; l'utilisation pendant un cours ne nécessite donc pas de téléchargement Internet.

## Composant tiers intégré

`runtime/livekit-server.exe` est le binaire Windows x64 non modifié de LiveKit Server **v1.13.11**, accompagné de sa licence Apache 2.0.

- Archive officielle : <https://github.com/livekit/livekit/releases/tag/v1.13.11>
- SHA-256 de l'archive ZIP : `5d071ea43339f0140d24a66f0b2ff8a7bf6c4d0dceb19b30abca835670213667`
- SHA-256 du `livekit-server.exe` extrait : `7c63b07e78121edab0c81554af97754cc7abbaac05313cdb91c81f51287e16c2`
- Licence incluse : `runtime/LIVEKIT-LICENSE`

## Limites à ce stade

- La compilation `net10.0-windows` et une publication autonome `win-x64` avaient réussi en cross-build depuis Linux (0 erreur, 0 avertissement). L'EXE généré n'est pas versionné et **n'a pas été exécuté sous Windows**; récupérez-le dans l'artefact du workflow Windows après son exécution.
- Un test de fumée du serveur LiveKit v1.13.11 sous Linux avec la configuration de ports du prototype a validé le démarrage, `CreateRoom`, `ListParticipants` et `DeleteRoom`; cela ne remplace pas un test du binaire Windows.
- L'application Android existe et l'APK debug est compilé, mais aucune exécution sur appareil ni test de voix de bout en bout, Bluetooth, écouteurs, haut-parleur ou écho n'a encore été réalisé.
- La découverte mDNS, le pare-feu et le routage UDP/TCP n'ont pas été validés sur Windows ni sur le réseau de la salle.
- Les mesures de latence, gigue, pertes, débit et reconnexions par téléphone ne sont pas encore intégrées; elles devront venir du client Android.
- La capacité pour 200 élèves n'est pas démontrée. Le protocole de validation est dans `docs/TEST_PLAN.md`.
- Le prototype utilise `ws://`/HTTP en clair sur le LAN. Il applique une limitation simple de 8 échecs de connexion par minute et par IP/rôle, mais cela ne remplace pas TLS ni une revue de sécurité. Il est réservé à un banc d'essai sur un réseau de confiance isolé; avant un pilote avec une classe, ajouter TLS/pinning et auditer les protections.
- La limite logicielle actuelle de 220 participants sert à borner ce prototype et ne constitue pas une garantie de capacité.

## Documentation

- `docs/ETAT.md` — état et prochaine action;
- `docs/TEST_PLAN.md` — étapes de test réseau et montée en charge;
- `runtime/LIVEKIT-LICENSE` — licence du binaire intégré.
