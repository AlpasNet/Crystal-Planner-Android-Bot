# Migration depuis Crystal Planner Node.js

| Variable du projet d’origine | Champ Android |
|---|---|
| `DISCORD_TOKEN` | Jeton du bot Discord |
| `CHECK_INTERVAL_MINUTES` | Intervalle de synchronisation, minimum 15 |
| `TOPICS_CHANNEL_ID` | ID du salon Topics |
| `NOTICES_CHANNEL_ID` | ID du salon Notices |
| `MAINTENANCE_CHANNEL_ID` | ID du salon Maintenance |
| `UPDATES_CHANNEL_ID` | ID du salon Updates |
| `LINKSHELL_BOARD_ENABLED` | Activer la synchronisation Events |
| `LINKSHELL_CHANNEL_ID` | ID du salon Events |
| `LINKSHELL_GENERATOR_URL` | URL du générateur PHP |
| `LINKSHELL_CURRENT_FILE` ou URL du JSON | URL du JSON Events |
| `LINKSHELL_JSON_READ_DELAY_MS` | Délai après génération, en secondes |
| `RULES_ENABLED` | Activer la synchronisation Rules |
| `RULES_CHANNEL_ID` | ID du salon Rules |
| `RULES_JSON_URL` | URL du JSON Rules |
| `GUIDES_ENABLED` | Activer la synchronisation Guides |
| `GUIDES_CHANNEL_ID` | ID du salon Guides |
| `GUIDES_JSON_URL` | URL du JSON Guides |

## Différences importantes

1. Il n’y a plus de fichier `.env` sur Android.
2. Les données sont enregistrées dans les préférences privées de l’application.
3. Le jeton est chiffré séparément avec Android Keystore.
4. Les fichiers JSON doivent être accessibles par URL HTTPS ; Android ne lit pas les chemins locaux du serveur OVH.
5. L’intervalle périodique minimal passe de 10 à 15 minutes.
6. La logique Discord repose sur l’API REST v10 plutôt que sur `discord.js`.
7. Le stockage des publications Lodestone déjà vues et des empreintes JSON est local à l’appareil.

## Première synchronisation

Lors du premier lancement, les publications Lodestone récentes qui ne figurent pas encore dans le cache local peuvent être envoyées. Il est conseillé d’utiliser d’abord un salon Discord de test, puis de configurer les salons définitifs.
