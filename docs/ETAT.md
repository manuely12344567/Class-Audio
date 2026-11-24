# État du projet ClasseAudio

Mis à jour le 15 novembre 2026.

## Version publiée et retour utilisateur

- La release **v1.0.0** est publiée dans [`manuely12344567/Class-Audio`](https://github.com/manuely12344567/Class-Audio/releases/tag/v1.0.0), avec le package Windows et l'APK Android debug.
- L'utilisateur a démarré le logiciel et a réussi à se connecter avec un téléphone. Il n'a pas encore plusieurs téléphones pour valider le flux audio/modération en classe; il prévoit de tester et de donner un retour.
- Retour sur la v1 : interface trop classique, pas de marque/logo, et détection automatique sans succès; il a fallu saisir l'URL du PC ainsi que le code. L'objectif UX est que l'élève n'ait à saisir que le code.

## Itération de conception et découverte

- Identité visuelle mégaphone ajoutée aux vues Android et WPF; icône Android adaptative et icône Windows créées.
- Le tableau de bord Windows présente le code élève comme seule information à communiquer; le bouton **Copier le code** copie exclusivement ce code. L'adresse technique est repliée dans « Détails réseau · dépannage ».
- L'écran Android a été réorganisé en cartes, avec le code au premier plan et la saisie d'adresse cachée derrière les options de dépannage.
- La découverte Android est prévue en deux étapes : mDNS `_classeaudio._tcp.local`, puis balayage HTTP borné des sous-réseaux Wi-Fi/Ethernet actifs (jusqu'à trois interfaces, 512 hôtes candidates et 32 requêtes simultanées) en validant `GET /api/v1/session`. Le code n'est envoyé qu'après détection. La logique s'inspire du scan par étapes de LocalSend, sans utiliser ses routes de transfert de fichiers.
- La release v1.0.0 et son APK restent inchangés; aucune nouvelle release/tag n'est prévue avant les essais sur appareils. La preview reconstruite est `ClasseAudio-Android-preview-debug.apk` (signature debug, non testée sur appareil).
- L'APK preview a passé `:app:assembleDebug` avec le SDK Android 35; package `com.classeaudio.app.debug`, label `ClasseAudio`, SHA-256 `018586ed2677064c2b4a50220c24cba9fead84786b4c04908ad462840e45fbec`.
- Les workflows GitHub Actions Android et Windows ont réussi pour le commit `c91cff8` ([Android](https://github.com/manuely12344567/Class-Audio/actions/runs/45510596353), [Windows](https://github.com/manuely12344567/Class-Audio/actions/runs/45510596368)); les workflows ont produit leurs artefacts téléchargeables. Le build Windows inclut la dernière modification de palette dans `App.xaml`.

## État technique conservé

- Hôte WPF .NET 10 : lancement/arrêt de LiveKit local, API Kestrel, annonce DNS-SD/mDNS, génération de secrets locaux par session, tableau de bord et modération.
- Politique serveur : professeur = microphone; élève = `canPublish:false` et `canPublishData:false` tant qu'il n'a pas reçu l'autorisation. Un seul élève peut parler à la fois.
- Limitation de base : 8 échecs de jointure par minute et par IP/rôle. Aucun enregistrement audio n'est configuré.
- LiveKit Server Windows x64 v1.13.11 et licence Apache 2.0 versionnés dans `runtime/`.
- Les workflows `.github/workflows/windows-build.yml` et `android-build.yml` avaient tous deux réussi pour v1.0.0. Les binaires sont issus du code du commit `cdbd8a2`; les commits qui ont suivi ne contiennent que de la documentation.
- Test de fumée Linux de LiveKit avec la configuration prévue : démarrage, `CreateRoom`, `ListParticipants` et `DeleteRoom` validés.

## Validations encore requises

- Les builds Android et Windows ont réussi sur GitHub Actions pour le commit `c91cff8`; l'APK n'a pas été installé/testé sur téléphone et l'hôte Windows n'a pas été exécuté sur un PC réel.
- La v1.0.0 s'est connectée sur un appareil selon le retour utilisateur, mais la découverte automatique de cette version a échoué dans son environnement et a nécessité l'URL manuelle.
- La découverte mDNS, le balayage réseau et le pare-feu doivent être retestés sur le vrai réseau de la salle après reconstruction.
- Deux téléphones ou plus restent nécessaires pour vérifier l'écoute, les demandes de parole, l'autorisation/révocation et le routage audio réel.
- Bluetooth, écho, TLS, latence, gigue, pertes et débit ne sont pas validés; aucune capacité de 200 appareils n'est promise.

## Prochaines étapes

1. Installer `ClasseAudio-Android-preview-debug.apk` sur le téléphone (désinstaller la précédente app debug si Android refuse la mise à jour à cause de sa signature).
2. Avec le PC hôte et ce seul téléphone sur le même Wi-Fi, saisir le code uniquement et retester mDNS puis le balayage HTTP de secours; les changements de découverte n'exigent pas plusieurs téléphones.
3. Télécharger l'artefact Windows du workflow réussi, lancer l'hôte sur un PC réel et vérifier le tableau de bord ainsi que le bouton qui copie uniquement le code; conserver l'APK preview pour l'essai mobile.
4. Lorsque d'autres téléphones seront disponibles, tester l'audio, les demandes de parole, l'autorisation et la révocation. Ne pas publier de nouvelle release avant validation utilisateur; TLS/pinning et tests de charge restent nécessaires avant tout pilote.

Aucun identifiant utilisateur n'est inclus dans le dépôt. Les identifiants LiveKit sont générés localement pour chaque session.
