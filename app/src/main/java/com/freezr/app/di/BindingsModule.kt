package com.freezr.app.di

import android.content.Context
import com.freezr.app.data.repo.ContextRuleSource
import com.freezr.app.data.repo.LocalPartnerApprovalStub
import com.freezr.app.data.repo.LocationNeeds
import com.freezr.app.data.repo.PartnerApprovalProvider
import com.freezr.app.data.repo.SecretStore
import com.freezr.app.platform.security.KeystoreSecretStore
import com.freezr.app.data.repo.ProtectedPackagesSource
import com.freezr.app.data.repo.RuleChangeNotifier
import com.freezr.app.data.repo.UsageSource
import com.freezr.app.data.settings.SettingsRepository
import com.freezr.app.platform.EnforcementCoordinator
import com.freezr.app.platform.ReconcileStep
import com.freezr.app.platform.StateChangeListener
import com.freezr.app.platform.usage.UsageTracker
import com.freezr.app.platform.whitelist.WhitelistResolver
import com.freezr.app.data.repo.ContextRuleRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import com.freezr.app.domain.security.PinHasher
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun protectedSource(impl: WhitelistResolver): ProtectedPackagesSource
    @Binds abstract fun usageSource(impl: UsageTracker): UsageSource
    @Binds abstract fun ruleChangeNotifier(impl: EnforcementCoordinator): RuleChangeNotifier
    @Binds abstract fun contextRuleSource(impl: ContextRuleRepository): ContextRuleSource
    @Binds abstract fun locationNeeds(impl: ContextRuleRepository): LocationNeeds
    @Binds abstract fun secretStore(impl: KeystoreSecretStore): SecretStore
    @Binds abstract fun partnerApproval(impl: LocalPartnerApprovalStub): PartnerApprovalProvider

    @Multibinds abstract fun stateListeners(): Set<StateChangeListener>
    @Multibinds abstract fun reconcileSteps(): Set<ReconcileStep>

    companion object {
        @Provides fun context(@ApplicationContext ctx: Context): Context = ctx
        @Provides @Singleton fun pinHasher(): PinHasher = PinHasher()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object ReconcileStepsModule {
    @Provides @IntoSet
    fun whitelistStep(resolver: WhitelistResolver, settings: SettingsRepository): ReconcileStep = object : ReconcileStep {
        override val name = "whitelist"
        override suspend fun reconcile() {
            resolver.refresh()
            if (!settings.current().defaultsSeeded) {
                resolver.seedDefaults()
                settings.update { defaultsSeeded = true }
            }
        }
    }

    @Provides @IntoSet
    fun usageStep(tracker: UsageTracker): ReconcileStep = object : ReconcileStep {
        override val name = "usage"
        override suspend fun reconcile() = tracker.reconcile()
    }
}
