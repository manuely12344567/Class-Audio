# API locale — contrat prototype

Base URL annoncée par mDNS ou saisie manuelle : `http://<IP-du-PC>:8090`.
Le service WebRTC LiveKit est renvoyé par `GET /api/v1/session` sous la forme `ws://<IP-du-PC>:7880`.
Ces URL ne sont pas chiffrées : usage de banc d'essai sur réseau privé isolé uniquement.

## Découverte

`GET /api/v1/session` renvoie `serviceName`, `serviceType`, `room`, `liveKitUrl`, `apiPort`, `joinPath` et `hostAddress`. La réponse ne contient ni code de classe, ni PIN, ni secret API.

mDNS : type `_classeaudio._tcp.local`, port TCP `8090`. La résolution mDNS fournit l'hôte; Android doit ensuite appeler l'endpoint de session.

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

Un élève reçoit explicitement `canPublish:false` et `canPublishData:false`. Le professeur peut publier uniquement une piste microphone. Les identités LiveKit sont opaques; le nom affiché reste dans l'hôte local.

## Contrôles de parole

Toutes les routes ci-dessous demandent `Authorization: Bearer <controlToken>`.

- Élève : `POST /api/v1/speaking/request` pour demander la parole.
- Professeur : `GET /api/v1/speaking/requests` pour voir les demandes.
- Professeur : `GET /api/v1/participants` pour obtenir l'état du salon.
- Professeur : `POST /api/v1/speakers/{identity}/allow` pour permettre au demandeur de publier son microphone.
- Professeur : `POST /api/v1/speakers/{identity}/revoke` pour lui retirer le droit.

L'hôte refuse une deuxième autorisation tant qu'un élève est déjà actif. Le droit `canPublish` est modifié côté LiveKit; l'application Android ne doit pas se fier à une simple règle d'interface.

## Limites de sécurité du prototype

- HTTP/WS en clair : le code, le PIN et les jetons de contrôle sont visibles pour un observateur du réseau.
- La route de jointure limite à 8 échecs par minute et par adresse IP/rôle; c'est une défense de base, pas une protection contre un réseau malveillant.
- Avant un test avec des élèves : TLS local + certificat épinglé, revue des endpoints, rotation/expiration des jetons, et essai de reconnexion sur le réseau réel.
