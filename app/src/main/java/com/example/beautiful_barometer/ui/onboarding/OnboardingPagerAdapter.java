package com.example.beautiful_barometer.ui.onboarding;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.ScrollView;
import android.view.ViewGroup;
import android.widget.TextView;

import com.example.beautiful_barometer.ui.PressureGaugeView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.beautiful_barometer.R;
import com.google.android.material.card.MaterialCardView;

import java.util.List;

public final class OnboardingPagerAdapter extends RecyclerView.Adapter<OnboardingPagerAdapter.PageViewHolder> {

    private final List<OnboardingPage> pages;

    public OnboardingPagerAdapter(List<OnboardingPage> pages) {
        this.pages = pages;
    }

    @NonNull
    @Override
    public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_onboarding_page, parent, false);
        return new PageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
        OnboardingPage page = pages.get(position);
        holder.title.setText(page.titleRes);
        holder.body.setText(page.bodyRes);
        holder.showPreview(page.previewType);
        holder.bindStaticPreviewData();
        holder.scrollToDescriptionOnStart();
    }

    @Override
    public int getItemCount() {
        return pages.size();
    }

    static final class PageViewHolder extends RecyclerView.ViewHolder {
        final ScrollView scrollView;
        final MaterialCardView previewShell;
        final View previewHome;
        final View previewGraph;
        final View previewNotifications;
        final View previewTrip;
        final View previewAdaptive;
        final View previewExport;
        final View previewThemeHelp;
        final PressureGaugeView previewHomeGauge;
        final PressureGaugeView previewTripGauge;
        final TextView title;
        final TextView body;

        PageViewHolder(@NonNull View itemView) {
            super(itemView);
            scrollView = (ScrollView) itemView;
            previewShell = itemView.findViewById(R.id.cardOnboardingPreviewShell);
            previewHome = itemView.findViewById(R.id.previewHome);
            previewGraph = itemView.findViewById(R.id.previewGraph);
            previewNotifications = itemView.findViewById(R.id.previewNotifications);
            previewTrip = itemView.findViewById(R.id.previewTrip);
            previewAdaptive = itemView.findViewById(R.id.previewAdaptive);
            previewExport = itemView.findViewById(R.id.previewExport);
            previewThemeHelp = itemView.findViewById(R.id.previewThemeHelp);
            previewHomeGauge = itemView.findViewById(R.id.previewHomeGauge);
            previewTripGauge = itemView.findViewById(R.id.previewTripGauge);
            title = itemView.findViewById(R.id.textOnboardingTitle);
            body = itemView.findViewById(R.id.textOnboardingBody);
        }

        void showPreview(int type) {
            previewHome.setVisibility(type == OnboardingPage.TYPE_HOME ? View.VISIBLE : View.GONE);
            previewGraph.setVisibility(type == OnboardingPage.TYPE_GRAPH ? View.VISIBLE : View.GONE);
            previewNotifications.setVisibility(type == OnboardingPage.TYPE_NOTIFICATIONS ? View.VISIBLE : View.GONE);
            previewTrip.setVisibility(type == OnboardingPage.TYPE_TRIP ? View.VISIBLE : View.GONE);
            previewAdaptive.setVisibility(type == OnboardingPage.TYPE_ADAPTIVE ? View.VISIBLE : View.GONE);
            previewExport.setVisibility(type == OnboardingPage.TYPE_EXPORT ? View.VISIBLE : View.GONE);
            previewThemeHelp.setVisibility(type == OnboardingPage.TYPE_THEME_HELP ? View.VISIBLE : View.GONE);
        }

        void bindStaticPreviewData() {
            if (previewHomeGauge != null) {
                previewHomeGauge.setPressure(1012.3);
            }
            if (previewTripGauge != null) {
                previewTripGauge.setPressure(1011.8);
            }
        }

        void scrollToDescriptionOnStart() {
            if (scrollView == null) {
                return;
            }
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        }
    }
}
