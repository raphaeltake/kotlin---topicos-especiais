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
                for (screen in listOf("team", "history", "notifications", "join", "success", "profile") + if (admin) listOf("members") else emptyList()) {
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
                onView(withId(R.id.btnTaskAction)).check(matches(withEffectiveVisibility(Visibility.GONE)))
                onView(withId(R.id.btnComment)).check(matches(withEffectiveVisibility(Visibility.GONE)))
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

    @Test fun commentBodyOpensEveryVersionWithOneArrowPerEdit() {
        val team = Workspace.Team()
        val task = team.tasks.first()
        task.comments.clear()
        task.comments.add(Workspace.Comment("Atual", mutableListOf("Original", "Segunda versão")))
        Workspace.teams.add(team)
        try {
            launch("task", team, task).use {
                onView(withId(R.id.tvCommentText1)).perform(click())
                onView(withId(R.id.commentHistoryVersions)).check { view, error ->
                    if (error != null) throw error
                    val versions = view as android.widget.LinearLayout
                    assertEquals(5, versions.childCount)
                    assertTrue(versions.getChildAt(1) is android.widget.ImageView)
                    assertTrue(versions.getChildAt(3) is android.widget.ImageView)
                    assertEquals("Original", versions.getChildAt(0).findViewById<android.widget.TextView>(R.id.tvVersionText).text.toString())
                    assertEquals("Atual", versions.getChildAt(4).findViewById<android.widget.TextView>(R.id.tvVersionText).text.toString())
                }
            }
        } finally { Workspace.teams.remove(team) }
    }

    @Test fun pastDateCannotBeSavedAndTodayCan() {
        val team = Workspace.Team()
        val task = team.tasks.first()
        val originalDate = task.date
        Workspace.teams.add(team)
        try {
            launch("task", team, task).use {
                onView(withId(R.id.btnEditTask)).perform(click())
                onView(withId(R.id.etDueDate)).check { view, error ->
                    if (error != null) throw error
                    (view as android.widget.EditText).setText(java.time.LocalDate.now().minusDays(1).format(DueDates.formatter))
                }
                onView(withId(R.id.btnSaveTask)).perform(click())
                assertEquals(originalDate, task.date)
                onView(withId(R.id.etDueDate)).check { view, error ->
                    if (error != null) throw error
                    val field = view as android.widget.EditText
                    assertNotNull(field.error)
                    field.setText(java.time.LocalDate.now().format(DueDates.formatter))
                }
                onView(withId(R.id.btnSaveTask)).perform(click())
                assertEquals(java.time.LocalDate.now().format(DueDates.formatter), task.date)
            }
        } finally { Workspace.teams.remove(team) }
    }

    @Test fun profileValidatesAndSavesRegistrationFields() {
        val old = Workspace.profile.copy()
        val team = Workspace.teams.first()
        try {
            launch("profile", team).use { scenario ->
                scenario.onActivity { activity ->
                    fun input(id: Int) = activity.findViewById<br.com.fatec.syncro.ui.SyncroTextInput>(id)
                    input(R.id.profileNameInput).input.setText("Maria")
                    input(R.id.profileEmailInput).input.setText("inválido")
                    activity.findViewById<View>(R.id.btnSaveProfile).performClick()
                    assertNotNull(input(R.id.profileEmailInput).error)
                    assertEquals(old.email, Workspace.profile.email)
                    input(R.id.profileEmailInput).input.setText("maria@example.com")
                    input(R.id.profilePasswordInput).input.setText("nova-senha")
                    input(R.id.profileConfirmPasswordInput).input.setText("diferente")
                    activity.findViewById<View>(R.id.btnSaveProfile).performClick()
                    assertNotNull(input(R.id.profileConfirmPasswordInput).error)
                    input(R.id.profileConfirmPasswordInput).input.setText("nova-senha")
                    activity.findViewById<View>(R.id.btnSaveProfile).performClick()
                    assertEquals("Maria", Workspace.profile.name)
                    assertEquals("maria@example.com", Workspace.profile.email)
                    assertEquals("nova-senha", Workspace.profile.password)
                }
            }
        } finally {
            Workspace.profile.name = old.name
            Workspace.profile.email = old.email
            Workspace.profile.password = old.password
        }
    }

    @Test fun emptyInvitationListCanContinueToTeam() {
        val team = Workspace.Team(name = "Equipe sem convidados", tasks = mutableListOf(),
            members = mutableListOf(Workspace.Member("Você", "Criador(a)")))
        Workspace.teams.add(team)
        try {
            ActivityScenario.launch<InviteTeamActivity>(Intent(ApplicationProvider.getApplicationContext(), InviteTeamActivity::class.java)
                .putExtra(Navigation.TEAM, team.id)).use {
                onView(withId(R.id.tvInviteAction)).check(matches(withText("Continuar sem convidar")))
                onView(withId(R.id.btnInvite)).perform(click())
                onView(withId(R.id.tvTeamName)).check(matches(withText(team.name)))
                assertEquals(1, team.members.size)
            }
        } finally { Workspace.teams.remove(team) }
    }

    @Test fun openingNotificationsClearsUnreadIndicator() {
        val unread = Workspace.hasUnreadNotifications
        try {
            Workspace.hasUnreadNotifications = true
            launch("team", Workspace.teams.first()).use {
                onView(withId(R.id.btnNotificationsTop)).check(matches(withContentDescription("Notificações não lidas")))
                onView(withId(R.id.btnNotificationsTop)).perform(click())
                assertFalse(Workspace.hasUnreadNotifications)
                androidx.test.espresso.Espresso.pressBack()
                onView(withId(R.id.btnNotificationsTop)).check(matches(withContentDescription("Notificações")))
            }
        } finally { Workspace.hasUnreadNotifications = unread }
    }
}
