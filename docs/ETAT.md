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
- Build croisé `net10.0-windows` réussi depuis Linux dans un tour précédent, mais `ClasseAudio.exe` n'est plus présent dans le snapshot actuel. Le code source, le script et le binaire LiveKit/licence restent dans le workspace.
- Création de l'application Android prototype professeur/élève, audio LiveKit, demandes/modération, découverte NSD et saisie manuelle; corrections Kotlin effectuées.
- Build `assembleDebug` réussi précédemment. APK debug présent dans le workspace et vérifié avec `apksigner`; deux avertissements NSD dépréciés.
- Workflows CI présents : `.github/workflows/windows-build.yml` produit l'application Windows publiée et l'installateur; `.github/workflows/android-build.yml` compile l'APK et téléverse les artefacts depuis GitHub Actions.
- Test de fumée Linux de LiveKit v1.13.11 avec la configuration prévue : démarrage, `CreateRoom`, `ListParticipants` et `DeleteRoom` validés.

## Non vérifié / non livré

- `ClasseAudio.exe` est absent du snapshot actuel; le workflow Windows n'a pas encore été lancé sur le dépôt GitHub. L'EXE n'a pas été exécuté sur un PC Windows et aucun installateur n'a été validé.
- L'APK n'a pas été installé ni exécuté sur appareil; pas de test audio réel, micro Bluetooth, écouteurs, haut-parleur ou écho.
- Une nouvelle compilation Android complète après `clean` a été tuée par la limite mémoire du worker pendant `packageDebug`. L'APK présent provient du `assembleDebug` réussi et sa signature a été vérifiée; les builds CI sont prévus pour contourner cette limite locale.
- mDNS et pare-feu non validés sur le réseau de salle; le réseau peut bloquer le multicast ou isoler les appareils.
- Les indicateurs par téléphone (latence, gigue, pertes, débit, interruptions/reconnexions) ne sont pas encore intégrés.
- Pas de test de charge à 200 téléphones; aucune capacité de 200 appareils n'est promise.
- Sécurité prototype : HTTP/WS en clair; TLS/pinning et revue de sécurité nécessaires avant un pilote avec une classe.

## Prochaine action

1. Pousser le dépôt vers GitHub avec les identifiants gérés par l'utilisateur, puis lancer les deux workflows dans l'onglet Actions et télécharger leurs artefacts.
2. Installer/tester l'APK sur deux téléphones Android et l'hôte sur un PC Windows x64 réel; valider réseau local, découverte et saisie manuelle.
3. Tester micro Bluetooth du professeur, écoute élèves, refus de publication par défaut, demandes, autorisation et révocation, et confirmer qu'un seul élève parle.
4. Ajouter TLS local/pinning Android avant tout pilote, vérifier la limitation des tentatives et contrôler qu'aucun PIN/jeton n'apparaît dans les journaux.
5. Mesurer Bluetooth, écho, latence, gigue, pertes, débit, interruptions et reconnexions; monter progressivement la charge jusqu'à environ 200 sur le réseau réel.

Les workflows GitHub Actions n'exigent pas de PAT : ils utilisent le jeton intégré de GitHub. Ne pas envoyer de token dans le chat. Le token déjà communiqué n'a pas été utilisé ni enregistré; le révoquer s'il est encore actif. Les identifiants LiveKit sont générés localement par session; avertir l'utilisateur si un secret externe devient réellement nécessaire.
