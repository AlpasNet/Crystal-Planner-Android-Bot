# Crystal Planner pour Android

Port Android natif du bot **Crystal Planner**. L’application reprend les fonctions principales du projet Node.js :

- surveillance des catégories **Topics**, **Notices**, **Maintenance** et **Updates** du Lodestone européen ;
- publication de messages Discord via l’API REST Discord v10 ;
- synchronisation des JSON **Events / Polls**, **Rules** et **Guides** ;
- appel facultatif du générateur PHP avant de lire le JSON Events ;
- comparaison stable des données en ignorant les champs d’horodatage ;
- nettoyage des salons avant republication des tableaux ;
- journal local réinitialisé à chaque cycle et rafraîchi automatiquement à la fin ;
- bouton protégé par confirmation pour vider les salons Lodestone ;
- état actif/inactif lu directement depuis WorkManager ;
- planification périodique avec WorkManager ;
- chiffrement du jeton Discord avec Android Keystore.
- intégration du logo Crystal Planner fourni dans l’interface, l’icône du lanceur et l’écran de démarrage Android.
- interface multilingue disponible en dix langues.

## Langues disponibles

L’application utilise automatiquement la langue du téléphone parmi les langues suivantes :

- Français — `fr` ;
- Anglais — `en` ;
- Allemand — `de` ;
- Espagnol — `es` ;
- Tagalog — `tl` ;
- Italien — `it` ;
- Portugais — `pt` ;
- Portugais brésilien — `pt-BR` ;
- Japonais — `ja` ;
- Chinois simplifié — `zh-CN`.

Sur Android 13 et versions ultérieures, la langue peut être choisie indépendamment de celle du téléphone depuis **Paramètres Android > Applications > Crystal Planner > Langue**. Sur Android 8 à 12, Crystal Planner suit la langue principale du système. Le français reste la langue de repli lorsqu’aucune langue prise en charge ne correspond.

## Choix technique

Le dépôt d’origine est un bot Node.js destiné à tourner en permanence sur un serveur. Android ne garantit pas l’exécution continue d’une application fermée. Cette adaptation utilise donc **WorkManager**, avec un intervalle minimal de **15 minutes**, et propose un bouton **Exécuter maintenant**.

Cette version est adaptée à une installation privée. Ne publiez jamais un APK contenant ou utilisant un jeton de bot partagé publiquement. Pour une diffusion sur un store, conservez le bot sur votre serveur OVH et utilisez Android uniquement comme interface d’administration.

## Prérequis

- Android Studio récent ;
- JDK 17 ;
- Android SDK 36 ;
- appareil sous Android 8.0 ou supérieur (`minSdk 26`) ;
- un bot Discord déjà invité sur le serveur avec les permissions nécessaires.

## Ouvrir et compiler

1. Ouvrir le dossier `Crystal-Planner-Android` dans Android Studio.
2. Laisser Android Studio synchroniser Gradle et installer les composants SDK demandés.
3. Sélectionner un appareil ou un émulateur.
4. Lancer la configuration `app`.

Pour produire un APK :

1. ouvrir **Build > Build App Bundle(s) / APK(s) > Build APK(s)** ;
2. récupérer l’APK de debug dans `app/build/outputs/apk/debug/`.

## Configuration dans l’application

### Configuration générale

- **Jeton du bot Discord** : le jeton seul est recommandé. La version 1.0.4 accepte aussi `Bot ...`, `DISCORD_TOKEN=...`, les guillemets et les blocs de code ;
- utiliser **Tester le jeton** avant de lancer la synchronisation ;
- **Intervalle** : 15 minutes minimum.

### Actualités Lodestone

Renseigner les identifiants des salons Discord pour :

- Topics ;
- Notices ;
- Maintenance ;
- Updates.

Un champ vide désactive simplement la catégorie correspondante. Le bouton **Effacer tous les messages Lodestone** vide uniquement les salons Topics, Notices, Maintenance et Updates renseignés, après confirmation.

### Events / Polls

- activer la case ;
- renseigner l’ID du salon ;
- renseigner l’URL HTTPS du générateur PHP, facultative ;
- renseigner l’URL HTTPS du JSON contenant `messages` ;
- choisir le délai entre la génération et la lecture du JSON.

### Rules et Guides

Chaque bloc demande :

- l’activation ;
- l’ID du salon Discord ;
- l’URL HTTPS du JSON contenant le tableau `messages`.

## Format JSON attendu

```json
{
  "generated_at": "2026-07-25T12:00:00Z",
  "messages": [
    {
      "content": "Texte facultatif",
      "embeds": [
        {
          "title": "Titre",
          "description": "Description",
          "color": 13215820,
          "image": {
            "url": "https://example.com/image.png"
          }
        }
      ]
    }
  ]
}
```

Les limites principales de Discord sont normalisées automatiquement : contenu, embeds, champs, titres et descriptions. Les mentions sont désactivées par défaut pour éviter les notifications accidentelles.


## Dépannage du jeton Discord

Si le journal affiche **HTTP 401**, le jeton est invalide ou a été réinitialisé :

1. ouvrir le **Discord Developer Portal** ;
2. sélectionner l’application du bot ;
3. ouvrir **Bot** ;
4. utiliser **Reset Token** ;
5. copier le nouveau **Bot Token** dans l’application Android ;
6. appuyer sur **Tester le jeton**.

Ne pas utiliser l’Application ID, la Public Key, le Client Secret ou un jeton OAuth2 utilisateur. Lorsqu’un jeton est réinitialisé, l’ancien cesse immédiatement de fonctionner, y compris dans l’ancien bot Node.js.

## Permissions Discord nécessaires

Le bot doit au minimum pouvoir :

- voir les salons ;
- lire l’historique ;
- envoyer des messages ;
- intégrer des liens ;
- gérer les messages dans les salons Events, Rules et Guides si leur nettoyage est activé.

## Correspondance avec le `.env` d’origine

Consulter [`MIGRATION.md`](MIGRATION.md).

## État et journal

- **ACTIVÉE** signifie qu’une tâche périodique WorkManager est réellement enregistrée ;
- **DÉSACTIVÉE** signifie qu’aucune tâche périodique active n’existe ;
- l’intervalle enregistré apparaît sous l’état ;
- le journal précédent est effacé au début d’un cycle ;
- l’affichage reste stable pendant l’exécution, puis est remplacé par le journal complet du nouveau cycle à sa fin.

## Limites Android

- la synchronisation périodique n’est pas une horloge exacte ; Android peut la différer ;
- une fermeture forcée de l’application dans les paramètres Android peut empêcher les tâches jusqu’à la prochaine ouverture ;
- certains constructeurs appliquent des restrictions supplémentaires d’économie d’énergie ;
- la version Android utilise l’API REST Discord et non `discord.js` ou la Gateway Discord ;
- l’application ne reçoit donc pas de commandes ou d’événements entrants Discord.

## Structure principale

```text
app/src/main/java/net/alpas/crystalplanner/
├── MainActivity.java
├── discord/DiscordApi.java
├── storage/
├── sync/
└── util/
```

## Sécurité

- seules les URL HTTPS sont acceptées ;
- le jeton est chiffré avec une clé non exportable d’Android Keystore ;
- les mentions Discord sont neutralisées ;
- les identifiants Discord sont validés avant utilisation ;
- les réponses HTTP 429 respectent `Retry-After`.
