package im.vector.app.nightly

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.features.home.HomeActivity
import im.vector.app.features.home.NightlyProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.inject.Inject
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class GithubUpdateProxy @Inject constructor(
        @ApplicationContext private val context: Context
) : NightlyProxy {

    override fun canDisplayPopup(): Boolean = true

    override fun isNightlyBuild(): Boolean = true

    // CORRECTION : La signature exacte d'Element ne prend pas d'argument ou prend VectorHomeActivity
    override fun updateApplication() {
        // Cette méthode est appelée par Element sans arguments. 
        // Pour Skiris, nous avons besoin d'une Activity pour afficher la Dialog.
        // On laisse vide ici ou on log, car l'appel réel doit venir d'une Activity.
    }

    // AJOUT : Une méthode spécifique pour Skiris que vous appellerez depuis HomeActivity
    override fun checkAndInstallUpdate(activity: HomeActivity) {
        activity.lifecycleScope.launch(Dispatchers.Main) {
            val updateInfo = checkGitHubForUpdate()

            if (updateInfo != null && isNewerVersion(updateInfo.first)) {
                MaterialAlertDialogBuilder(activity)
                        .setTitle("Mise à jour disponible")
                        .setMessage("Une nouvelle version de Skiris (${updateInfo.first}) est disponible. Voulez-vous l'installer ?")
                        .setPositiveButton("Télécharger") { _, _ ->
                            startDownload(updateInfo.second)
                        }
                        .setNegativeButton("Plus tard", null)
                        .show()
            }
        }
    }

    private suspend fun checkGitHubForUpdate(): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            // Assurez-vous d'avoir ajouté <string name="github_api_url">...</string> dans strings.xml ou config.xml
            val url = "https://api.github.com/repos/DevopsDiris/skiris-android/releases/latest"
            val response = URL(url).readText()
            val json = JSONObject(response)
            val tagName = json.getString("tag_name")
            val assets = json.getJSONArray("assets")
            if (assets.length() == 0) return@withContext null
            val downloadUrl = assets.getJSONObject(0).getString("browser_download_url")
            Pair(tagName, downloadUrl)
        } catch (e: Exception) {
            null
        }
    }

    private fun isNewerVersion(remoteTag: String): Boolean {
        // remoteTag vient de GitHub (ex: "v1.0.45")

        val currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
                ?: return false // sécurité si jamais null

        // Nettoyage
        val cleanRemote = remoteTag
                .removePrefix("v")
                .trim()

        // On enlève les suffixes type "-sonar", "-debug", etc.
        val cleanCurrent = currentVersion
                .split("-")
                .first()
                .trim()

        // Log pour debug
        println("SkirisUpdate: Comparaison Remote($cleanRemote) vs Current($cleanCurrent)")

        // Comparaison simple (comme tu l’avais prévu)
        return cleanRemote != cleanCurrent
    }

    private fun startDownload(url: String) {
        // 1. Définition de la destination
        val destination = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "skiris-update.apk")
        if (destination.exists()) destination.delete()

        // 2. Configuration de la requête de téléchargement
        val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Mise à jour Skiris")
                .setDescription("Téléchargement de la nouvelle version...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(destination))

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = manager.enqueue(request)

        // 3. Création du récepteur pour installer l'APK une fois fini
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    installApk(c, destination)
                    try {
                        context.unregisterReceiver(this)
                    } catch (e: Exception) {
                        // Évite un crash si le receiver est déjà désenregistré
                    }
                }
            }
        }

        // 4. CORRECTION : Enregistrement sécurisé selon la version d'Android
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Pour Android 13+ (API 33), on doit spécifier si le receiver est exporté ou non
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // Pour les versions antérieures
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    private fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        context.startActivity(intent)
    }
}
