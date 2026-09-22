# CrashSleuth — Spécification

**Version 7 — 22/09/2026** — v6 + recherche du coupable côté client (jeu lancé par l'outil).

### Changements depuis la v6
- **Côté client** (§6.3, jalon 4 commencé) : l'outil installe lui-même Minecraft et Fabric dans son propre cache à partir des manifestes officiels (Mojang, Fabric), fichiers vérifiés par SHA-1, bibliothèques et natives choisies pour la machine (macOS, Windows, Linux, x86 ou ARM) ; le launcher du joueur n'est jamais utilisé ni modifié. Le jeu s'ouvre dans une **petite fenêtre** (427 × 240), en joueur hors ligne, et se ferme tout seul.
- **Verdict sans humain côté client** : prêt quand l'écran titre est construit (atlas des blocs et moteur de son, repérés dans les vrais journaux de 1.21.1 vanilla et Fabric) ; planté si le jeu s'arrête ou si le loader affiche son écran d'erreur (Fabric lancé avec `fabric.noGui`, sinon il attend le joueur).
- **Commandes** : `crashsleuth run-client <dossier de jeu> --minecraft 1.21.1 --loader fabric` (un lancement de test) et `crashsleuth bisect <dossier de jeu> --client --minecraft 1.21.1` (la recherche, avec les mêmes options que côté serveur : `--repeat`, `--parallel`…).
- **Labo client** (`lab/client_lab.py`), lancé sur l'ordinateur d'Holo795 (il faut un écran) : **6 scénarios réels, 6 réussis** avec de vrais mods de Modrinth et un mod de test Fabric qui ne dépend que de Fabric Loader :
  - 10 mods populaires (Sodium, Lithium, ModMenu…) : démarrage en 19 s, aucun constat ;
  - **cas réel trouvé** : la dernière version de Sodium (0.8.13) avec la dernière version d'Iris publiée pour 1.21.1 (1.8.8) ne démarre pas, Iris exige Sodium 0.6.x : diagnostic précis ;
  - dépendance manquante (Zoomify sans YetAnotherConfigLib), mod qui plante à l'initialisation : coupables désignés ;
  - arrêt net sans aucune trace : coupable trouvé en 7 lancements (moins d'une minute) ; conflit entre deux mods : les deux trouvés en 24 lancements (4,7 min).
- **Messages Fabric** : toutes les formes de « requires … of mod » sont lues (« any 0.6.x version », « version 2.0 or later »…).
- **Confidentialité du corpus** : les chemins qui nomment l'utilisateur de l'ordinateur (cache, dossier personnel) sont retirés des journaux client.
- À venir côté client : NeoForge et Forge (leur installeur client), crashs en jeu (entrée dans un monde), application de bureau.

### Changements de la v6 (rappel)
- **Index des mixins** (§6.1) : lecture du bytecode (ASM) des classes mixin déclarées par chaque mod (Fabric, NeoForge, Forge, manifeste) : qui modifie quelle méthode du jeu, et comment (remplacement, injection, redirection, MixinExtras). Commande `crashsleuth mixins <dossier ou pack>`. Sur les vrais packs (Create Plus 2 780 injections de 73 mods, Cobbleverse 3 637 de 113 mods, en 2 secondes), il trouve le conflit Lithium / ModernFix sur `Biome.getTemperature` que les journaux des vrais serveurs confirment.
- **Principe confirmé par le réel** : un conflit de mixins vu dans les fichiers n'est **pas** un diagnostic. Sur les 4 conflits statiques de Create Plus, un seul a lieu au démarrage : les mods désactivent eux-mêmes leurs mixins en double quand ils voient l'autre. Ces conflits sont affichés par `crashsleuth mixins`, jamais signalés comme cause.
- **Attribution par les mixins** (§6.2) : un crash dont la trace ne montre que du code de Minecraft désigne comme premiers suspects les mods qui modifient ces méthodes (confiance faible, à confirmer par la recherche du coupable). Les méthodes injectées nommées d'après leur mod (`handler$…$lithium$…`) sont attribuées directement.
- **Crashs aléatoires** (§6.3) : `--repeat N` relance chaque ensemble testé jusqu'à N fois ; un ensemble n'est sain que s'il ne plante jamais. Le serveur complet est relancé jusqu'à N fois pour reproduire.
- **Lancements en parallèle** : `--parallel N`, chaque essai dans son propre dossier et sur son propre port ; les ensembles d'une même étape sont testés ensemble et le résultat reste identique à la recherche séquentielle.
- **Campagne réelle, 5 recherches sur 5 réussies** (lancée sur le Mac pendant une coupure du site du R320 : même labo, mêmes serveurs officiels et plugins de Modrinth) :
  - crash qui n'arrive qu'à 60 % des démarrages, `--repeat 6` : coupable trouvé en 17 lancements (6 min) ;
  - conflit entre deux plugins, `--parallel 2` : trouvé en 5,5 min contre 7 min un par un (22 lancements contre 20 : le parallélisme teste des ensembles d'avance) ;
  - non-régression : arrêt silencieux en 7 lancements, Create Plus en 3.
- **Labo** : un cas du corpus est remplacé en entier à chaque passage ; les scénarios « crash aléatoire » sont marqués pour le rejeu.

### Changements de la v5 (rappel)
- **Recherche du coupable côté serveur** (§6.3, jalon 3) : `crashsleuth bisect <dossier>`. Nouveaux modules `core-runner` (copie de travail, lancement, verdict) et `core-bisect` (recherche). L'outil lance une copie du serveur (port libre, sans console distante, fichiers de l'utilisateur jamais modifiés), décide seul si le lancement est sain, planté ou bloqué, teste d'abord les suspects de l'analyse puis réduit l'ensemble par delta debugging (ddmin), ce qui trouve aussi les conflits entre plusieurs mods ou plugins. Chaque essai est complété par les dépendances nécessaires. Un crash différent de celui cherché n'est jamais pris pour lui (empreinte : issue, situation, exception).
- **Modpacks sans installation** (§4) : `.mrpack` Modrinth (fichiers téléchargés depuis les hôtes autorisés par le format, vérifiés par SHA-1, mis en cache), zip CurseForge (fichiers embarqués seulement : les autres passent par l'API CurseForge, qui demande une clé), zip d'un dossier de serveur. Côté serveur ou client (`--side`).
- **Java requise lue dans le bytecode** : version des classes de chaque jar, comparée à la Java qui tourne (si le journal la donne) ou à celle que demande la version de Minecraft.
- **Liste de mods client confirmés** pour NeoForge et Forge, qui ne peuvent pas le déclarer : chaque entrée dit comment elle a été confirmée.
- **Test réel sur les fichiers publiés** : le `.mrpack` officiel de Create Plus est analysé en 30 secondes sans rien installer et le mod client qui fait planter son serveur est désigné ; côté client, le même pack ressort sain (Sinytra Connector reconnu, même quand son identifiant est dans un jar embarqué). Cobbleverse ressort sain des deux côtés.
- **Troisième campagne réelle : 34 scénarios, 34 réussis**, dont 3 recherches de coupable sans intervention humaine :
  - Paper 1.21.1 avec 14 plugins (12 réels, WorldEdit en dépendance, un plugin de test) qui s'arrête net sans aucune trace dans les journaux : coupable trouvé en 7 lancements (8 min) ;
  - même serveur, crash seulement quand deux plugins sont installés ensemble : les deux trouvés en 20 lancements (24 min) ;
  - Create Plus tel que publié (106 mods) : Status Effect Bars confirmé en 3 lancements (3 min), grâce au suspect désigné par l'analyse.
- **Leçon du réel pour la recherche** : l'identité d'un crash ne se calcule que sur ce qui a terminé le lancement (crash report, erreur fatale du démarrage). Deux faux coupables sont venus d'erreurs sans rapport avec l'arrêt, dont une reconnue avec certitude par une signature.
- **Découverte réelle** : DecentHolograms 2.10.1, la dernière version, n'est pas chargé du tout sur Paper 1.21.1. Il contient des classes Java 25 (adaptateurs pour Minecraft 26) que le remappeur de Paper ne sait pas lire : le serveur démarre sans lui, avec pour seule trace une erreur technique. Nouvelle signature ; et la Java requise d'un jar est désormais celle de sa classe d'entrée, pas la plus haute trouvée dans ses adaptateurs optionnels.

### Changements de la v4 (rappel)
- **Vérification des fichiers installés** (§6.1, jalon 2 commencé) : `crashsleuth analyze <dossier>` lit les métadonnées de chaque jar de `mods/` et `plugins/` (y compris les jars embarqués) et croise ce qu'il trouve avec les journaux. Nouveau module `core-inventory`, et `core-engine` qui réunit journaux et fichiers.
- **Règles tirées du réel**, pas de la théorie (§6.1) : Fabric et NeoForge gardent sans erreur la version la plus récente d'un mod en double ; Fabric ignore en silence les mods client sur un serveur ; NeoForge accepte JEI 1.21.1 alors que JEI déclare une plage qui exclut 1.21.1. L'analyse statique reproduit ces comportements pour ne jamais accuser à tort.
- **Base de signatures** (§7) : fichier de données `signatures.json` chargé par un détecteur générique, 20 signatures au départ dont une partie adaptée de codex-minecraft (MIT, attribution dans `NOTICE`).
- **Nouvelles situations** : `MOD_CONFLICT` (mods incompatibles, paquets Java en double), `WORLD_DOWNGRADE` (monde d'une version plus récente), `WORLD_DUPLICATE` (deux mondes avec le même `uid.dat`).
- **Nouveaux détecteurs issus des vrais journaux** : gel du thread principal par un plugin (watchdog de Paper), erreurs répétées de tâches, d'événements ou de commandes de plugins, doublons (Paper, Forge, NeoForge), mauvaise version de Minecraft (Fabric, Forge, NeoForge), jar qui n'est pas un plugin dans `plugins/`.
- **Deuxième campagne réelle** : **31 scénarios réels, 31 réussis** après corrections, dont 16 nouveaux : doublons (Fabric, NeoForge, Paper), mauvaise version de Minecraft, mod NeoForge dans `plugins/`, plugins de test qui plantent au démarrage, échouent en boucle ou bloquent le thread principal, serveurs 26.3 (vanilla, Paper, Fabric), Java 21 trop ancienne pour 26.3, et deux modpacks complets (Cobbleverse 168 mods sur Fabric, Create Plus 106 mods sur NeoForge) sains puis privés d'une bibliothèque. Les modpacks ont révélé deux faux positifs corrigés : bibliothèque sans métadonnée propre mais avec des mods embarqués (Kotlin for Forge), exceptions sans gravité sur un serveur sain. Le labo a trouvé un vrai défaut dans un modpack publié : les fichiers serveur de Create Plus embarquent Status Effect Bars, un mod client qui empêche le serveur de démarrer.

### Changements de la v3 (rappel)
- **Tests en conditions réelles obligatoires** (§11) : un labo construit de vrais serveurs depuis les sources officielles, installe de vrais mods et plugins depuis Modrinth, les casse volontairement, les lance avec la bonne version de Java, puis vérifie que CrashSleuth trouve la bonne cause. Les journaux obtenus forment un **corpus réel** rejoué en intégration continue ; les démarrages sains doivent ne donner **aucun** constat.
- **Premier lot : 14 scénarios réels, 14 réussis** (vanilla, Paper, Purpur, NeoForge, Forge 1.20.1, Fabric) après correction des détecteurs à partir des vrais messages.

### Changements de la v2 (rappel)
- **Admins de serveurs d'abord** : la recherche du coupable côté serveur (jalon 3) passe avant l'application de bureau et la recherche côté client (jalon 4).
- **Lancement côté client** : l'outil installe lui-même la bonne version de Minecraft et le bon loader dans son espace de travail, avec un profil local de test ; il ne réutilise ni ne modifie jamais le launcher ou l'installation du joueur.
- **Fenêtres de jeu** : chaque essai côté client ouvre le jeu dans une petite fenêtre qui se ferme toute seule ; l'utilisateur est prévenu avant le lancement de la recherche.

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
- **Analyse des mixins** (bytecode via ASM) : qui modifie quelle méthode, détection des chevauchements incompatibles. **Fait (v6)** : index et commande `mixins` ; les chevauchements sont informatifs (voir changements v6).
- Version de Java requise lue dans les fichiers `.class`.
- Empreintes SHA-1/SHA-512 comparées à Modrinth et CurseForge (hors ligne possible, avec cache).
- **Fait (v4)** : lecture des descripteurs Fabric, Quilt, NeoForge, Forge, Bukkit, Paper, BungeeCord et Velocity, jars embarqués compris ; détection de la plateforme et des versions depuis le dossier ; doublons, mauvais loader ou mauvais dossier, dépendances manquantes, mauvaise version de Minecraft, `api-version` trop récente, jars illisibles. L'inventaire (sans les jars) est exportable en JSON et partageable.
- **Principe** : l'analyse statique ne signale que ce que le loader réel refuserait. Les comportements observés au labo priment sur les spécifications : doublon gardé en silence (Fabric, NeoForge) = avertissement ; mod client sur serveur Fabric = ignoré ; plage Minecraft Maven (Forge, NeoForge) tolérée sur la même version mineure.

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
- **Fait (v5), côté serveur** : copie de travail faite une fois (sans mods, plugins, journaux, ni mondes sauf `--with-world`), puis un dossier neuf par essai où les gros dossiers en lecture (`libraries`, `versions`, `cache`) sont liés et non copiés ; commande de lancement détectée comme le ferait le script du serveur (`@user_jvm_args.txt @libraries/.../unix_args.txt` pour NeoForge et Forge, `-jar` sinon) ; verdict : prêt (`Done (…)!` puis arrêt propre après quelques secondes), planté, ou délai dépassé ; budget de lancements. À venir : lancements en parallèle, crashs aléatoires (relances), côté client.
- Côté serveur : lancement sans écran. Côté client : l'outil installe lui-même la version et le loader dans son espace de travail, lance le jeu en **petite fenêtre** (écran virtuel possible sous Linux), avec un monde de test généré et un profil local de test ; l'installation du joueur n'est jamais touchée. L'utilisateur est prévenu avant que les fenêtres s'ouvrent.
- **Reproduction en jeu** (plus tard) : sur une copie du monde, téléportation aux coordonnées du crash, chargement du chunk, puis recherche du coupable dans ce scénario.

### 6.4 Gels et lag
- Lecture des dumps de threads (watchdog de Paper, spark, `jstack`) : thread principal bloqué, verrou attendu, cycle de verrous, mod ou plugin en cause.
- Lecture des profils spark : répartition par mod ou plugin.
- Pour un serveur qui tourne : capture automatique au moment du gel (intégration Pulse côté Pterodactyl).

---

## 7. Base de signatures

- Fichiers de données versionnés dans le dépôt, relus comme du code. **Fait (v4)** : `core-logs/src/main/resources/crashsleuth/signatures.json`, 20 signatures ; chaque signature donne une expression régulière, la situation, la confiance, les groupes qui désignent les coupables et les détails, et éventuellement un conseil propre.
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
  - En place : `core-model` (situations, rapports, textes) · `core-logs` (découpage, attribution, détecteurs, signatures) · `core-inventory` (descripteurs des jars, packs, Java du bytecode, contrôles statiques) · `core-engine` (réunit journaux et fichiers, corpus réel) · `core-runner` (copie de travail, lancement serveur, verdict) · `core-bisect` (suspects puis ddmin, dépendances respectées) · `cli`.
  - À venir : analyse ASM et mixins dans `core-inventory` · lancement côté client dans `core-runner` · `desktop` (Compose).
- Téléchargement des versions de Minecraft, loaders et serveurs (Paper, Purpur…) à la demande, avec cache.
- **Tests en conditions réelles** (`lab/`) : de vrais serveurs (vanilla, Paper, Purpur, Fabric, NeoForge, Forge) téléchargés depuis les sources officielles, de vrais mods et plugins depuis Modrinth, cassés volontairement (dépendance retirée, mauvais loader, mod client sur serveur, mauvaise version de Java, mémoire insuffisante…), lancés dans Docker avec la bonne version de Java. Chaque journal obtenu est anonymisé et rangé dans `lab/corpus` avec l'inventaire des jars installés et la réponse attendue, puis rejoué en CI. Des plugins de test (`lab/fixtures`) provoquent les cas impossibles à obtenir avec de vrais plugins sains : exception au démarrage, tâche qui échoue en boucle, thread principal bloqué. Des modpacks entiers de Modrinth (Cobbleverse 168 mods, Create Plus 106 mods) servent de référence sans faux positif. Les démarrages sains servent à garantir l'absence de faux positifs. Les mods et serveurs ne sont jamais versionnés, seulement les journaux.
- Tests unitaires en complément, pour les formats rares ou difficiles à provoquer.
- Traductions : fichiers de ressources, anglais par défaut, français inclus.

---

## 12. Jalons

| # | Jalon | Terminé quand… |
|---|---|---|
| 0 | Fondations | Dépôt, licence MIT, build Gradle, CI GitHub, README anglais + français, spec |
| 1 | Analyse des journaux | Un crash report NeoForge, Fabric, Forge, Paper ou vanilla donne la cause et le coupable (CLI), signatures de base + import codex-minecraft — **en grande partie fait (v4)** |
| 2 | Vérification avant lancement | Dossier ou pack → dépendances, versions, loader, doublons, Java, client/serveur, plugins, empreintes — **commencé (v4)** : dossier installé ; restent packs zip/mrpack, Java des `.class`, mixins, empreintes |
| 3 | Recherche du coupable, serveur | Serveur NeoForge, Fabric, Forge ou Paper qui plante → coupable trouvé sans intervention, conflits à deux inclus — **fait (v6)** pour Paper et NeoForge, crashs aléatoires et parallèle compris ; reste : crashs en jeu (monde, joueurs) |
| 4 | Application de bureau + recherche côté client | Un joueur glisse son pack, l'outil trouve seul le mod fautif — **commencé (v7)** : recherche côté client pour vanilla et Fabric en ligne de commande |
| 5 | Gels, lag, web, Discord, Pterodactyl | Dump de thread → coupable ; bot et version web en ligne |
| 6 | IA | IA locale et clé personnelle ; IA hébergée si financement |

---

## 13. Règles du projet

- Commits en anglais, Conventional Commits, auteur Holo795, **aucune mention d'outil ou d'assistant** dans les commits, le code ou la documentation.
- La spec est mise à jour à chaque changement de conception.

## 14. Décisions prises

- Nom **CrashSleuth**, dépôt public `Holo795/CrashSleuth`, licence MIT.
- Kotlin (JVM), interface Compose Multiplatform, anglais par défaut + français.
- Vanilla, serveurs à plugins et modpacks traités à égalité ; versions et loaders principaux d'abord.
- **Admins de serveurs d'abord**, joueurs ensuite.
- Côté client : **installations propres à l'outil**, **petite fenêtre** à chaque essai.
- IA en dernier recours seulement.
