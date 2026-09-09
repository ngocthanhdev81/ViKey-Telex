package dev.ngocthanhgl.vikey.app.settings.about

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ngocthanhgl.vikey.BuildConfig
import dev.ngocthanhgl.vikey.R
import dev.ngocthanhgl.vikey.app.LocalNavController
import dev.ngocthanhgl.vikey.app.Routes
import dev.ngocthanhgl.vikey.app.settings.SettingsScaffold
import dev.ngocthanhgl.vikey.app.settings.components.M3ClickablePreference
import dev.ngocthanhgl.vikey.app.settings.components.SettingsDivider
import dev.ngocthanhgl.vikey.clipboardManager
import dev.ngocthanhgl.vikey.lib.util.launchUrl
import org.florisboard.lib.android.stringRes
import org.florisboard.lib.compose.FlorisCanvasIcon
import org.florisboard.lib.compose.stringRes

@Composable
fun AboutScreen() {
    SettingsScaffold(title = stringRes(R.string.about__title)) {
        val context = LocalContext.current
        val navController = LocalNavController.current
        val clipboardManager by context.clipboardManager()
        val appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        Column(
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 32.dp),
        ) {
            FlorisCanvasIcon(
                modifier = Modifier.requiredSize(64.dp),
                iconId = R.mipmap.floris_app_icon,
                contentDescription = stringRes(R.string.about__app_icon_content_description),
            )
            Text(
                text = stringRes(R.string.floris_app_name),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(28.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            M3ClickablePreference(
                icon = Icons.Rounded.Info,
                title = stringRes(R.string.about__version__title),
                onClick = {
                    try {
                        clipboardManager.addNewPlaintext(appVersion)
                        Toast.makeText(context, R.string.about__version_copied__title, Toast.LENGTH_SHORT).show()
                    } catch (e: Throwable) {
                        Toast.makeText(
                            context,
                            context.stringRes(R.string.about__version_copied__error, "error_message" to e.message),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
            SettingsDivider()
            M3ClickablePreference(
                icon = Icons.Rounded.History,
                title = stringRes(R.string.about__changelog__title),
                onClick = { context.launchUrl(R.string.florisboard__changelog_url, "version" to BuildConfig.VERSION_NAME) },
            )
            SettingsDivider()
            M3ClickablePreference(
                icon = Icons.Rounded.Code,
                title = stringRes(R.string.about__repository__title),
                onClick = { context.launchUrl(R.string.florisboard__repo_url) },
            )
            SettingsDivider()
            M3ClickablePreference(
                icon = Icons.Rounded.Description,
                title = stringRes(R.string.about__project_license__title),
                onClick = { navController.navigate(Routes.Settings.ProjectLicense) },
            )
            SettingsDivider()
            M3ClickablePreference(
                icon = Icons.Rounded.Description,
                title = stringRes(id = R.string.about__third_party_licenses__title),
                onClick = { navController.navigate(Routes.Settings.ThirdPartyLicenses) },
            )
        }
    }
}
