# CrashSleuth — Spécification

**Version 1 — 21/09/2026** — première version.

> CrashSleuth trouve pourquoi Minecraft plante et **qui** est en cause, pour les joueurs comme pour les serveurs : vanilla, serveurs à plugins et modpacks. Il analyse, vérifie avant lancement et, si besoin, **relance le jeu ou le serveur tout seul** en retirant des mods ou des plugins jusqu'à désigner le coupable.

Outil gratuit et open source (licence MIT), dépôt `github.com/Holo795/CrashSleuth`.

---

## 1. Pourquoi ce projet

Recherche de l'existant faite le 21/09/2026 (détail dans `docs/PRIOR_ART.md`) :

| Besoin | Ce qui existe | Ce qui manque |
|---|---|---|
| Analyser un log | mclo.gs + codex-minecraft, Crash Assistant, Not Enough Crashes, HMCL, outils web à IA fermés | Chaque outil a ses propres règles, aucune base ouverte partagée |
| Trouver le mod coupable | Prism (PR en cours), mod-bisect-tool, FabricBinarySearchTool, Quilt Bisect | **Tous demandent à l'utilisateur « ça a planté ? » après chaque lancement**, sauf Quilt Bisect (Quilt client 1.20.1-1.20.2, licence fermée, abandonné) |
| Trouver le plugin coupable | Rien, seulement des guides manuels | Tout |
| Conflit entre deux mods | Quilt Bisect uniquement | Un outil maintenu, multi-loader |
| Vérifier un pack avant lancement | Outils partiels (dépendances seulement) | Plages de versions, mauvais loader, doublons, mods client sur serveur, version de Java, conflits de mixins, empreintes Modrinth/CurseForge **réunis** |
| Gels et lag | spark, watchdog de Paper (rapports bruts) | La lecture automatique : « le plugin X bloque le thread principal » |

**Nos différences** :
1. Recherche du coupable **sans intervention humaine** : l'outil décide seul si un lancement a réussi ou planté.
2. Serveurs à plugins et proxys traités **à égalité** avec les modpacks.
3. **Conflits à plusieurs mods** détectés (delta debugging).
4. Contrôle complet avant lancement, utilisable aussi en **intégration continue** par les auteurs de packs.
5. **Base de signatures ouverte** et versionnée, compatible avec les règles de codex-minecraft (MIT).
6. IA en **dernier recours**, jamais indispensable, et à coût quasi nul.

---

## 2. Qui l'utilise et comment

| Public | Usage type |
|---|---|
| **Joueur** | « Mon modpack plante au lancement » → glisse le dossier dans l'application → réponse en clair |
| **Admin de serveur** | « Mon serveur ne démarre plus depuis que j'ai ajouté des plugins » → ligne de commande sur la machine → rapport |
| **Auteur de modpack** | Vérifie son pack à chaque modification, dans sa CI |
| **Support (Discord, hébergeurs)** | Colle un log → diagnostic instantané (bot, version web) |

**Trois modes**, qui s'enchaînent automatiquement quand c'est utile :

1. **Analyser** (secondes) : un crash report ou un log → cause, mod ou plugin en cause, solution.
2. **Vérifier** (secondes) : un dossier ou un pack → problèmes détectés sans rien lancer.
3. **Chercher le coupable** (minutes) : lancements successifs en retirant des mods ou plugins → le ou les coupables, prouvés.

---

## 3. Périmètre de départ

Versions et plateformes principales d'abord, les autres ensuite.

| Plateforme | Versions au départ | Java |
|---|---|---|
| Vanilla (client et serveur) | 1.20.1, 1.21.1, dernière 1.21.x, 26.x | 17 / 21 / 25 |
| Paper, Spigot, Purpur (serveur à plugins) | 1.20.1, 1.21.x, 26.x | 17 / 21 / 25 |
| NeoForge | 1.21.1 | 21 |
| Forge | 1.20.1 | 17 |
| Fabric | 1.20.1, 1.21.1, dernière 1.21.x | 17 / 21 |

**Ensuite** : Quilt, Folia, proxys Velocity et BungeeCord (recherche du plugin coupable côté proxy), versions plus anciennes (1.16.5, 1.18.2, 1.19.2, très utilisées en modpacks), Sponge.

---

## 4. Ce qu'on peut lui donner

- Un **dossier** : `mods/`, `plugins/`, dossier complet de serveur ou d'instance.
- Un **modpack** : zip CurseForge, `.mrpack` Modrinth, instance Prism / MultiMC / ATLauncher / FTB, ou l'**ID ou le lien** d'un pack (l'outil télécharge lui-même).
- Des **journaux** : `crash-reports/*.txt`, `logs/latest.log`, `debug.log`, `hs_err_pid*.log` (crash natif de Java), logs du launcher, rapports spark, sortie du watchdog.
- Plus tard : une **copie du monde**, pour reproduire un crash survenu en jeu.

L'outil **détecte seul** la plateforme, la version de Minecraft, le loader et la version de Java à partir de ce qu'on lui donne.

---

## 5. Catalogue des situations

Chaque situation a un identifiant stable (utilisé par les signatures et les rapports).

**Démarrage**
- `DEP_MISSING` dépendance absente · `DEP_VERSION` version hors plage · `DEP_CYCLE` dépendances circulaires.
- `WRONG_LOADER` mod d'un autre loader (Fabric dans NeoForge…) · `WRONG_MC` mauvaise version de Minecraft.
- `DUPLICATE` même mod en deux versions, ou embarqué deux fois (jar-in-jar).
- `JAVA_VERSION` Java trop ancienne ou trop récente (lue dans les fichiers `.class`).
- `CLIENT_ONLY_ON_SERVER` mod client seulement installé sur un serveur (et l'inverse).
- `MIXIN_CONFLICT` deux mods qui modifient la même méthode de façon incompatible.
- `PLUGIN_API` plugin compilé pour une API plus récente, `plugin.yml` invalide, dépendance `depend` manquante, plugin Paper-only sur Spigot.
- `CORRUPT_JAR` jar illisible ou différent de l'original (empreintes Modrinth/CurseForge).
- `CONFIG_BROKEN` fichier de configuration illisible · `DATAPACK_BROKEN` datapack ou recette invalide.

**Chargement du monde et connexion**
- `REGISTRY_MISMATCH` contenu différent entre client et serveur · `MOD_MISMATCH` mods différents à la connexion.
- `CORRUPT_CHUNK`, `CORRUPT_ENTITY` avec coordonnées et dimension, `CORRUPT_PLAYERDATA`.

**En jeu**
- `TICK_ENTITY` / `TICK_BLOCK_ENTITY` crash sur une entité ou un bloc précis (position, mod d'origine).
- `OUT_OF_MEMORY` avec recommandation de mémoire selon la taille du pack · `STACK_OVERFLOW`.
- `RENDER` pilote graphique, OpenGL, shaders (Iris, Oculus), packs de ressources.
- `NATIVE_CRASH` crash natif de la Java (pilotes, LWJGL, système).

**Sans crash**
- `HANG` gel du thread principal : lecture des dumps de threads (watchdog, spark, jstack) → qui bloque, sur quoi, depuis quand.
- `DEADLOCK` deux threads qui s'attendent mutuellement.
- `LAG` lecture d'un profil spark → mods ou plugins qui consomment.
- `LOG_SPAM`, `SILENT_ERROR` erreurs répétées sans crash (recettes, structures, tâches de plugins).

---

## 6. Le moteur

### 6.1 Analyse statique (sans lancement)
- Lecture des descripteurs : `neoforge.mods.toml`, `META-INF/mods.toml`, `fabric.mod.json`, `quilt.mod.json`, `plugin.yml`, `paper-plugin.yml`, `velocity-plugin.json`, `bungee.yml`.
- Graphe des dépendances et vérification des plages de versions (syntaxes Maven, Fabric et semver).
- **Index des classes** de tous les jars (y compris jar-in-jar) : toute ligne d'une trace se rattache à son mod ou plugin.
- **Analyse des mixins** (bytecode via ASM) : qui modifie quelle méthode, détection des chevauchements incompatibles.
- Version de Java requise lue dans les fichiers `.class`.
- Empreintes SHA-1/SHA-512 comparées à Modrinth et CurseForge (hors ligne possible, avec cache).

### 6.2 Analyse des journaux
- Découpage du log (démarrage, chargement, jeu, arrêt), extraction des exceptions et des « Caused by ».
- **Retraduction des noms obfusqués** (mappings officiels Mojang, SRG, intermediary) pour des traces lisibles.
- Attribution : lignes de la trace → mod ou plugin, **y compris via les mixins** quand la trace ne montre que du code de Minecraft.
- Application des **signatures** (§7) → cause, confiance, solution.

### 6.3 Recherche du coupable (lancements)
- **Détection du verdict sans humain** : crash report créé, code de sortie, exception fatale dans le log, délai dépassé, ou ligne de réussite (`Done (…)!` côté serveur, écran titre ou monde chargé côté client).
- **Bisection consciente des dépendances** : on retire toujours une bibliothèque avec ses dépendants, on raisonne par groupes cohérents.
- **Départ intelligent** : les suspects désignés par l'analyse sont testés en premier (souvent 2-3 lancements au lieu de 10).
- **Conflits à plusieurs** : algorithme de delta debugging (ddmin) quand aucun mod seul n'explique le crash.
- **Crashs aléatoires** : relances multiples, score de confiance.
- Lancements **en parallèle** quand la machine le permet, **cache** des combinaisons déjà testées, **budget** de temps réglable.
- **Isolation** : le travail se fait sur une **copie** ; les fichiers de l'utilisateur ne sont jamais modifiés.
- Côté serveur : lancement sans écran. Côté client : fenêtre de jeu (ou écran virtuel sous Linux), monde de test généré, compte hors ligne local.
- **Reproduction en jeu** (plus tard) : sur une copie du monde, téléportation aux coordonnées du crash, chargement du chunk, puis recherche du coupable dans ce scénario.

### 6.4 Gels et lag
- Lecture des dumps de threads (watchdog de Paper, spark, `jstack`) : thread principal bloqué, verrou attendu, cycle de verrous, mod ou plugin en cause.
- Lecture des profils spark : répartition par mod ou plugin.
- Pour un serveur qui tourne : capture automatique au moment du gel (intégration Pulse côté Pterodactyl).

---

## 7. Base de signatures

- Fichiers de données versionnés dans le dépôt (`signatures/`), relus comme du code.
- Une signature = conditions (exception, classe, message, mod et versions, plateforme) → situation, explication (anglais + français), solution, liens (issue du mod, page de téléchargement).
- **Import des règles codex-minecraft** (MIT) pour démarrer avec une base riche.
- Recherche automatique dans les issues GitHub et Modrinth du mod en cause : « déjà signalé ici, corrigé en 2.3 ».
- Contributions : un cas résolu peut proposer une nouvelle signature, validée par relecture avant fusion.

---

## 8. IA, à coût quasi nul

Ordre de passage, du gratuit au payant :

1. **Moteur et signatures** (toujours, hors ligne, instantané).
2. **IA locale optionnelle** : petit modèle téléchargé à la demande, tourne sur la machine de l'utilisateur, explique en langage simple.
3. **Clé personnelle** : l'utilisateur branche sa propre clé d'API pour une analyse poussée.
4. **IA hébergée** (plus tard) : seulement si 1 à 3 échouent ; on envoie un **résumé anonymisé**, jamais le log brut ; **cache par signature** (un même crash vu par mille personnes = un seul appel) ; chaque réponse validée devient une signature gratuite. Financement : dons, partenariats avec des hébergeurs de serveurs.

---

## 9. Formes du produit

| Forme | Rôle | Quand |
|---|---|---|
| **Cœur** (bibliothèque Kotlin) | Toute l'intelligence, réutilisable | Dès le départ |
| **CLI** `crashsleuth` | Serveurs, CI, scripts ; sortie texte, JSON et SARIF | Dès le départ |
| **Application de bureau** (Compose Multiplatform) | Joueurs : glisser un dossier, suivre la recherche, rapport clair | Jalon 3 |
| **Version web** | Coller un log, analyse immédiate (modes 1 et 2 seulement) | Jalon 5 |
| **Bot Discord** | Support des communautés de modpacks | Jalon 5 |
| **Extension Pterodactyl** | Via Pulse : diagnostic depuis le panel, capture des gels | Jalon 5 |

**Rapport** : lisible par un débutant (cause, coupable, quoi faire), plus un détail technique repliable et un export partageable (texte, JSON, lien mclo.gs).

---

## 10. Vie privée et sécurité

- **Local par défaut** : rien ne sort de la machine sans action explicite.
- Anonymisation avant tout envoi : pseudos, UUID, IP, chemins, jetons.
- Aucun envoi automatique de statistiques.
- Les jars ne sont exécutés que dans les lancements de test, sur une copie, jamais « à côté » de l'installation de l'utilisateur.

---

## 11. Architecture technique

- **Kotlin (JVM 21)**, Gradle en Kotlin DSL, multi-modules :
  - `core-model` (situations, rapports) · `core-static` (descripteurs, dépendances, ASM, mixins) · `core-logs` (découpage, retraduction, attribution) · `core-signatures` · `core-runner` (lancement client/serveur, verdict) · `core-bisect` (bisection et ddmin) · `cli` · `desktop` (Compose).
- Téléchargement des versions de Minecraft, loaders et serveurs (Paper, Purpur…) à la demande, avec cache.
- Tests : packs d'exemple qui plantent volontairement (un par situation), lancés en CI.
- Traductions : fichiers de ressources, anglais par défaut, français inclus.

---

## 12. Jalons

| # | Jalon | Terminé quand… |
|---|---|---|
| 0 | Fondations | Dépôt, licence MIT, build Gradle, CI GitHub, README anglais + français, spec |
| 1 | Analyse des journaux | Un crash report NeoForge, Fabric, Forge, Paper ou vanilla donne la cause et le coupable (CLI), signatures de base + import codex-minecraft |
| 2 | Vérification avant lancement | Dossier ou pack → dépendances, versions, loader, doublons, Java, client/serveur, plugins, empreintes |
| 3 | Recherche du coupable, serveur | Serveur NeoForge, Fabric, Forge ou Paper qui plante → coupable trouvé sans intervention, conflits à deux inclus |
| 4 | Application de bureau + recherche côté client | Un joueur glisse son pack, l'outil trouve seul le mod fautif |
| 5 | Gels, lag, web, Discord, Pterodactyl | Dump de thread → coupable ; bot et version web en ligne |
| 6 | IA | IA locale et clé personnelle ; IA hébergée si financement |

---

## 13. Règles du projet

- Commits en anglais, Conventional Commits, auteur Holo795, **aucune mention d'outil ou d'assistant** dans les commits, le code ou la documentation.
- La spec est mise à jour à chaque changement de conception.

## 14. Questions ouvertes

1. Côté client, la recherche du coupable ouvre une fenêtre de jeu à chaque essai (sauf sous Linux avec écran virtuel) : acceptable, ou mode « fenêtre réduite » à creuser ?
2. Pour lancer le client, l'outil réutilise-t-il le launcher de l'utilisateur (Prism, officiel…) ou installe-t-il ses propres versions avec un compte hors ligne local ?
3. Priorité du premier public : admins de serveurs (jalon 3 d'abord) ou joueurs (jalon 4 d'abord) ?
