# État du projet ClasseAudio

Mis à jour le 15 novembre 2026.

## Fait

- Vérification de PocketPad : son Android et son application WPF Windows sont orientés manettes/streaming d'écran, sans rapport avec le projet audio. Aucun fichier PocketPad n'a été modifié.
- Création d'un dépôt séparé : `/home/user/classe-audio`.
- Ajout du binaire officiel LiveKit Server Windows x64 v1.13.11 et de sa licence; SHA-256 vérifié à l'extraction.
- Écriture d'un hôte WPF .NET 10 : lancement/arrêt du serveur, génération de clés locales par session, API Kestrel, annonce mDNS, tableau de bord, codes séparés professeur/élève.
- Ajout d'une API locale pour l'inscription des clients Android futurs, la file de demandes de parole et l'autorisation/révocation serveur.
- Politique de publication : professeur = microphone uniquement; élève = `canPublish:false` et `canPublishData:false` jusqu'à l'autorisation; verrou serveur pour un seul élève actif.
- Limitation de base : 8 échecs de jointure par minute et par IP/rôle. Aucun enregistrement audio configuré.
- Le code du projet a été poussé sur `main` dans le dépôt `manuely12344567/Class-Audio` (commit initial `9f96b81`, puis correctifs CI Android).
- L'EXE local avait disparu du snapshot. Le workflow Windows a réussi sur le commit final et téléversé `ClasseAudio.exe`, son dossier `runtime/` et l'installateur dans `ClasseAudio-Windows-x64` (run [45501770193](https://github.com/manuely12344567/Class-Audio/actions/runs/45501770193)). SHA-256 de l'EXE : `9e02c504e58310c482bb41b86d001d798b923e07b79bbdd23e1e0e1cab31616e`; installateur : `b51b0e3ebec84a3276d2e7b244d36f75f4b927b35f9c4d2164444f59532b28f5`.
- Le workflow Android a réussi et téléversé `app-debug.apk` dans `ClasseAudio-Android-debug` (run [45501116169](https://github.com/manuely12344567/Class-Audio/actions/runs/45501116169)). SHA-256 de l'APK : `7d87eb9743a48d487f6280766902b8c0e7fa43df2b405f2d21e60751b5538c16`. Le binaire de CI reste signé en debug.
- Création de l'application Android prototype professeur/élève, audio LiveKit, demandes/modération, découverte NSD et saisie manuelle; corrections Kotlin effectuées.
- Les workflows sont versionnés : `.github/workflows/windows-build.yml` et `.github/workflows/android-build.yml`. Le code Windows et Android, README, docs, scripts et binaire LiveKit sont sur GitHub.
- Test de fumée Linux de LiveKit v1.13.11 avec la configuration prévue : démarrage, `CreateRoom`, `ListParticipants` et `DeleteRoom` validés.

## Non vérifié / non livré

- L'EXE Windows et l'installateur ont été compilés sur GitHub Actions, mais ni l'un ni l'autre n'a été exécuté sur un PC Windows réel.
- L'APK n'a pas été installé ni exécuté sur appareil; pas de test audio réel, micro Bluetooth, écouteurs, haut-parleur ou écho.
- Un `clean assembleDebug` local a été interrompu par la limite mémoire du workspace. Le workflow Android sur GitHub, lui, a réussi et produit l'APK téléchargeable.
- mDNS et pare-feu non validés sur le réseau de salle; le réseau peut bloquer le multicast ou isoler les appareils.
- Les indicateurs par téléphone (latence, gigue, pertes, débit, interruptions/reconnexions) ne sont pas encore intégrés.
- Pas de test de charge à 200 téléphones; aucune capacité de 200 appareils n'est promise.
- Sécurité prototype : HTTP/WS en clair; TLS/pinning et revue de sécurité nécessaires avant un pilote avec une classe.

## Prochaine action

1. Télécharger les artefacts depuis les runs Windows et Android liés ci-dessus.
2. Installer/tester l'APK sur deux téléphones Android et l'hôte sur un PC Windows x64 réel; valider réseau local, découverte et saisie manuelle.
3. Tester micro Bluetooth du professeur, écoute élèves, refus de publication par défaut, demandes, autorisation et révocation, et confirmer qu'un seul élève parle.
4. Ajouter TLS local/pinning Android avant tout pilote, vérifier la limitation des tentatives et contrôler qu'aucun PIN/jeton n'apparaît dans les journaux.
5. Mesurer Bluetooth, écho, latence, gigue, pertes, débit, interruptions et reconnexions; monter progressivement la charge jusqu'à environ 200 sur le réseau réel.

Les builds GitHub Actions n'exigent pas de PAT : GitHub leur fournit un jeton limité au workflow. Le push initial a été autorisé par le flux d'appareil GitHub CLI; les identifiants temporaires du sandbox seront supprimés après publication. Le PAT communiqué précédemment n'a pas été utilisé ni enregistré; le révoquer s'il est encore actif. La session OAuth GitHub CLI peut aussi être révoquée dans les paramètres GitHub. Les identifiants LiveKit sont générés localement par session.
