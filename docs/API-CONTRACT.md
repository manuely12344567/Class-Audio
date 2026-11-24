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
