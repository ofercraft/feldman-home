package com.feldman.ha.ui.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.feldman.motion.navigation.Dest
import com.feldman.motion.navigation.DestBackStack
import com.feldman.motion.navigation.MotionNavigationPage
import com.feldman.motion.navigation.MotionNavigationState
import com.feldman.motion.navigation.Navigator
import com.feldman.motion.navigation.paneMetadata
import kotlin.reflect.KClass

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AppNavHost(
    backStack: DestBackStack,
    destinations: List<AppDest>,
    navigationState: MotionNavigationState<Dest>,
    dashboardBackgroundColor: Color,
    modifier: Modifier = Modifier,
    // The adaptive strategy requires an Activity or WindowContext.
    adaptive: Boolean = true
) {
    val sceneStrategies = if (adaptive) {
        listOf(rememberListDetailSceneStrategy<Dest>())
    } else {
        emptyList()
    }

    NavDisplay(
        backStack = backStack.backStack,
        modifier = modifier,
        onBack = { backStack.pop() },
        sceneStrategies = sceneStrategies,
        transitionSpec = navigationState.transitionSpec(),
        popTransitionSpec = navigationState.popTransitionSpec(),
        predictivePopTransitionSpec = navigationState.predictivePopTransitionSpec(),
        entryProvider = entryProvider {
            for (destInstance in destinations) {
                @Suppress("UNCHECKED_CAST")
                val clazz = destInstance::class as KClass<Dest>

                addEntryProvider(
                    clazz = clazz,
                    clazzContentKey = { it },
                    metadata = destInstance.paneMetadata()
                ) { dest ->
                    MotionNavigationPage(
                        state = navigationState,
                        destination = dest,
                        backgroundColor = if (dest is AppDest.Dashboard) {
                            dashboardBackgroundColor
                        } else {
                            MaterialTheme.colorScheme.background
                        }
                    ) {
                        dest.Content(
                            onNavigate = object : Navigator {
                                override fun invoke(dest: Dest, resetStack: Boolean) {
                                    if (resetStack) {
                                        backStack.resetTo(dest)
                                    } else {
                                        backStack.navigate(dest)
                                    }
                                }
                            },
                            onBack = { backStack.pop() },
                            onFabAction = {}
                        )
                    }
                }
            }
        }
    )
}