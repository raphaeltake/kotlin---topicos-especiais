package br.com.fatec.syncro

import android.content.Intent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    private fun launch(screen: String, team: Workspace.Team, task: Workspace.Task? = null): ActivityScenario<WorkspaceActivity> {
        return ActivityScenario.launch(Intent(ApplicationProvider.getApplicationContext(), WorkspaceActivity::class.java)
            .putExtra(Navigation.SCREEN, screen).putExtra(Navigation.TEAM, team.id).putExtra(Navigation.TASK, task?.id))
    }

    @Test fun everyFullScreenInflatesForBothRoles() {
        for (admin in listOf(true, false)) {
            val team = Workspace.Team(admin = admin)
            Workspace.teams.add(team)
            try {
                for (screen in listOf("team", "history", "notifications", "join", "success") + if (admin) listOf("members") else emptyList()) {
                    launch(screen, team).use { scenario ->
                        scenario.onActivity { assertNotNull(it.findViewById<View>(R.id.btnBack)) }
                    }
                }
                for (task in team.tasks) launch("task", team, task).use { scenario ->
                    scenario.onActivity { assertNotNull(it.findViewById<View>(R.id.tvTaskTitle)) }
                }
            } finally { Workspace.teams.remove(team) }
        }
    }

    @Test fun taskActionsAdvanceToReadOnlyFinishedScreen() {
        val team = Workspace.Team()
        Workspace.teams.add(team)
        val task = team.tasks.first()
        try {
            launch("task", team, task).use {
                onView(withId(R.id.btnTaskAction)).perform(click())
                assertEquals(Workspace.Status.IN_PROGRESS, task.status)
                onView(withId(R.id.btnTaskAction)).perform(click())
                assertEquals(Workspace.Status.FINISHED, task.status)
                onView(withId(R.id.tvStatusBadge)).check(matches(withText("Finalizada")))
            }
        } finally { Workspace.teams.remove(team) }
    }

    @Test fun cancelDeletionPreservesTask() {
        val team = Workspace.Team()
        Workspace.teams.add(team)
        val task = team.tasks.first()
        try {
            launch("task", team, task).use {
                onView(withId(R.id.btnDeleteTask)).perform(click())
                onView(withId(R.id.btnCancel)).perform(click())
                assertTrue(team.tasks.contains(task))
                onView(withId(R.id.tvTaskTitle)).check(matches(withText(task.title)))
            }
        } finally { Workspace.teams.remove(team) }
    }
}
