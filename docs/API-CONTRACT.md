# API locale — contrat prototype

Base de l'API locale : `http://<IP-du-PC>:8090`.
Le service WebRTC LiveKit est renvoyé par `GET /api/v1/session` sous la forme `ws://<IP-du-PC>:7880`.
Ces URL ne sont pas chiffrées : usage de banc d'essai sur réseau privé isolé uniquement. L'application mobile les découvre; l'élève n'a normalement à saisir que le code de classe.

## Découverte automatique

`GET /api/v1/session` renvoie `serviceName`, `serviceType`, `room`, `liveKitUrl`, `apiPort`, `joinPath` et `hostAddress`. La réponse ne contient ni code de classe, ni PIN, ni secret API. Elle sert à confirmer qu'un hôte découvert est bien une instance ClasseAudio.

La découverte Android suit deux étapes, inspirées de l'approche « smart scan » de LocalSend :

1. Recherche DNS-SD/mDNS du type `_classeaudio._tcp.local` (port API TCP `8090`).
2. Si aucun hôte n'est confirmé après une courte attente, balayage HTTP borné des sous-réseaux IPv4 Wi-Fi/Ethernet actifs, avec `GET /api/v1/session` sur le port `8090`. La version Android limite le balayage à trois interfaces et 512 adresses candidates, avec un maximum de 32 requêtes en parallèle. Aucun code de classe ni PIN n'est envoyé pendant la découverte.

La méthode HTTP de secours est spécifique à ClasseAudio : il s'agit de reprendre la stratégie multi-méthodes de LocalSend, pas d'implémenter les routes de transfert de fichiers LocalSend. Si mDNS et le balayage direct échouent (isolation Wi-Fi, pare-feu, VLAN ou sous-réseau inhabituel), la saisie manuelle reste disponible dans les options de dépannage.

## Rejoindre la session

`POST /api/v1/join` — JSON :

```json
{
  "displayName": "Élève 12",
  "role": "student",
  "joinCode": "<code affiché sur le PC>"
}
```

Pour le professeur, ajouter `"role":"teacher"` et le `teacherPin` affiché sur le PC. Chaque requête reçoit un jeton d'accès LiveKit de quatre heures et un jeton de contrôle aléatoire, gardé en mémoire par l'hôte. Ne jamais écrire ces jetons dans les journaux ni les transmettre hors du réseau local.

Réponse (champs illustratifs, valeurs générées à chaque session) :

```json
{
  "liveKitUrl": "ws://192.168.1.20:7880",
  "room": "classe-123456",
  "identity": "student-<opaque-id>",
  "role": "student",
  "accessToken": "<JWT signé localement>",
  "controlToken": "<secret opaque temporaire>",
  "tokenExpiresInSeconds": 14400
}
```

Tous les jetons de jointure sont explicitement en lecture seule (`canPublish:false`, `canPublishData:false`). Après la connexion LiveKit, le professeur appelle `POST /api/v1/teacher/activate`; l'hôte vérifie que ce participant est dans la room puis active côté serveur son seul droit de publier le microphone. L'hôte n'autorise qu'un professeur actif et, séparément, un seul élève. Les identités LiveKit sont opaques; le nom affiché reste dans l'hôte local.

## Contrôles de parole

Toutes les routes ci-dessous demandent `Authorization: Bearer <controlToken>`.

- Professeur : `POST /api/v1/teacher/activate` après connexion LiveKit; le serveur autorise le micro d'un seul professeur à la fois.
- Professeur : `POST /api/v1/teacher/deactivate` avant de quitter; le serveur retire le droit du professeur et de l'élève actif.
- Élève : `POST /api/v1/speaking/request` pour demander la parole.
- Professeur actif : `GET /api/v1/speaking/requests` pour voir les demandes.
- Professeur actif : `GET /api/v1/participants` pour obtenir l'état du salon.
- Professeur actif : `POST /api/v1/speakers/{identity}/allow` pour permettre au demandeur de publier son microphone.
- Professeur actif : `POST /api/v1/speakers/{identity}/revoke` pour lui retirer le droit.

L'hôte sérialise les changements de permission et refuse une seconde autorisation élève tant qu'un élève est actif. Le tableau de bord Windows revalide périodiquement les participants; si le professeur disparaît, l'hôte retire le droit de publication d'un élève encore connecté avant de libérer les rôles. Les jetons seuls ne permettent à aucun client de contourner ces règles : le droit `canPublish` est modifié côté LiveKit; l'interface Android ne constitue pas la protection.

## Audio Android — réglages et diagnostic

- La publication vocale utilise le preset LiveKit Android `AudioPresets.SPEECH` de la version `2.29.0`, soit `24 000 bit/s` (24 kbit/s, et non kB/s), avec DTX et RED activés. C'est le débit cible du média audio, pas une promesse sur le débit total du Wi-Fi.
- Les options de capture WebRTC d'annulation d'écho, contrôle automatique du gain, filtre passe-haut et réduction du bruit restent activées par défaut. La réduction du bruit peut être désactivée sur le téléphone; le gain micro est un réglage numérique borné à 50–150 %.
- Le volume reçu est appliqué à chaque piste audio distante LiveKit sur le téléphone (0–200 %); il ne modifie pas le volume physique Android.
- L'écran de diagnostic relève les abonnements et échecs de piste, les états muets, les orateurs détectés, la qualité réseau et la sortie audio choisie. Le panneau du professeur résume aussi, pour chaque participant, l'état LiveKit, le droit de publication et le nombre de pistes publiées. Un abonnement confirmé ne garantit pas à lui seul que le haut-parleur de l'appareil émet un son audible.
- Le PC Windows héberge la signalisation/API locale et le relais LiveKit. Le débit cible et le traitement micro sont configurés par le client Android; aucun encodage ou DSP micro côté PC n'est prévu dans ce prototype. Une connexion Wi-Fi dégradée peut toujours provoquer coupures et reconnexions.

## Limites de sécurité du prototype

- HTTP/WS en clair : le code, le PIN et les jetons de contrôle sont visibles pour un observateur du réseau.
- La route de jointure limite à 8 échecs par minute et par adresse IP/rôle; c'est une défense de base, pas une protection contre un réseau malveillant.
- Avant un test avec des élèves : TLS local + certificat épinglé, revue des endpoints, rotation/expiration des jetons, et essai de reconnexion sur le réseau réel.
