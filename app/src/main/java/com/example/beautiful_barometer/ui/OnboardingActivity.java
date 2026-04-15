package com.example.beautiful_barometer.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.MarginPageTransformer;
import androidx.viewpager2.widget.ViewPager2;

import com.example.beautiful_barometer.R;
import com.example.beautiful_barometer.databinding.ActivityOnboardingBinding;
import com.example.beautiful_barometer.feedback.AppEventLogger;
import com.example.beautiful_barometer.ui.onboarding.OnboardingPage;
import com.example.beautiful_barometer.ui.onboarding.OnboardingPagerAdapter;
import com.example.beautiful_barometer.ui.onboarding.OnboardingPrefs;
import com.example.beautiful_barometer.util.ThemeController;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;
import java.util.List;

public class OnboardingActivity extends AppCompatActivity {

    private ActivityOnboardingBinding vb;
    private List<OnboardingPage> pages;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeController.applyToActivity(this);
        super.onCreate(savedInstanceState);
        vb = ActivityOnboardingBinding.inflate(getLayoutInflater());
        setContentView(vb.getRoot());

        pages = buildPages();
        vb.viewPagerOnboarding.setAdapter(new OnboardingPagerAdapter(pages));
        vb.viewPagerOnboarding.setOffscreenPageLimit(1);
        vb.viewPagerOnboarding.setPageTransformer(new MarginPageTransformer(dpToPx(12)));

        RecyclerView pagerRecyclerView = (RecyclerView) vb.viewPagerOnboarding.getChildAt(0);
        if (pagerRecyclerView != null) {
            pagerRecyclerView.setClipToPadding(false);
            pagerRecyclerView.setClipChildren(false);
            pagerRecyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        }

        new TabLayoutMediator(vb.tabDots, vb.viewPagerOnboarding, (tab, position) -> { }).attach();

        vb.btnSkipOnboarding.setOnClickListener(v -> finishOnboarding("skip"));
        vb.btnBackOnboarding.setOnClickListener(v -> {
            int index = vb.viewPagerOnboarding.getCurrentItem();
            if (index > 0) {
                vb.viewPagerOnboarding.setCurrentItem(index - 1, true);
            }
        });
        vb.btnNextOnboarding.setOnClickListener(v -> {
            int index = vb.viewPagerOnboarding.getCurrentItem();
            if (index >= pages.size() - 1) {
                finishOnboarding("finish");
            } else {
                vb.viewPagerOnboarding.setCurrentItem(index + 1, true);
            }
        });

        vb.viewPagerOnboarding.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateControls(position);
            }
        });

        updateControls(0);
        AppEventLogger.log(this, "ONBOARDING", "Real-screen onboarding opened");
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        int index = vb.viewPagerOnboarding.getCurrentItem();
        if (index > 0) {
            vb.viewPagerOnboarding.setCurrentItem(index - 1, true);
            return;
        }
        super.onBackPressed();
    }

    private void updateControls(int position) {
        boolean isFirst = position == 0;
        boolean isLast = position == pages.size() - 1;
        vb.btnBackOnboarding.setVisibility(isFirst ? View.INVISIBLE : View.VISIBLE);
        vb.btnNextOnboarding.setText(isLast ? R.string.onboarding_start : R.string.onboarding_next);
        vb.textStepOnboarding.setText(getString(R.string.onboarding_step_fmt, position + 1, pages.size()));
    }

    private List<OnboardingPage> buildPages() {
        List<OnboardingPage> out = new ArrayList<>();
        out.add(new OnboardingPage(
                R.string.onboarding_title_1,
                R.string.onboarding_body_1,
                OnboardingPage.TYPE_HOME
        ));
        out.add(new OnboardingPage(
                R.string.onboarding_title_2,
                R.string.onboarding_body_2,
                OnboardingPage.TYPE_GRAPH
        ));
        out.add(new OnboardingPage(
                R.string.onboarding_title_3,
                R.string.onboarding_body_3,
                OnboardingPage.TYPE_NOTIFICATIONS
        ));
        out.add(new OnboardingPage(
                R.string.onboarding_title_4,
                R.string.onboarding_body_4,
                OnboardingPage.TYPE_TRIP
        ));
        out.add(new OnboardingPage(
                R.string.onboarding_title_5,
                R.string.onboarding_body_5,
                OnboardingPage.TYPE_ADAPTIVE
        ));
        out.add(new OnboardingPage(
                R.string.onboarding_title_6,
                R.string.onboarding_body_6,
                OnboardingPage.TYPE_EXPORT
        ));
        out.add(new OnboardingPage(
                R.string.onboarding_title_7,
                R.string.onboarding_body_7,
                OnboardingPage.TYPE_THEME_HELP
        ));
        return out;
    }

    private void finishOnboarding(String source) {
        OnboardingPrefs.markCompleted(this);
        AppEventLogger.log(this, "ONBOARDING", "Completed via " + source);
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}
