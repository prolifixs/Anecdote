package io.gresse.hugo.anecdote;

import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.NavigationView;
import android.support.design.widget.Snackbar;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.PopupMenu;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import com.crashlytics.android.Crashlytics;
import com.squareup.otto.Subscribe;

import java.util.List;

import butterknife.Bind;
import butterknife.ButterKnife;
import io.fabric.sdk.android.Fabric;
import io.gresse.hugo.anecdote.event.BusProvider;
import io.gresse.hugo.anecdote.event.ChangeTitleEvent;
import io.gresse.hugo.anecdote.event.RequestFailedEvent;
import io.gresse.hugo.anecdote.event.UpdateAnecdoteFragmentEvent;
import io.gresse.hugo.anecdote.event.WebsitesChangeEvent;
import io.gresse.hugo.anecdote.event.network.NetworkConnectivityChangeEvent;
import io.gresse.hugo.anecdote.fragment.AboutFragment;
import io.gresse.hugo.anecdote.fragment.AnecdoteFragment;
import io.gresse.hugo.anecdote.fragment.SettingsFragment;
import io.gresse.hugo.anecdote.fragment.WebsiteChooserFragment;
import io.gresse.hugo.anecdote.fragment.WebsiteDialogFragment;
import io.gresse.hugo.anecdote.model.Website;
import io.gresse.hugo.anecdote.service.WebsiteApiService;
import io.gresse.hugo.anecdote.service.AnecdoteService;
import io.gresse.hugo.anecdote.service.ServiceProvider;
import io.gresse.hugo.anecdote.util.NetworkConnectivityListener;
import io.gresse.hugo.anecdote.util.SpStorage;


public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener, NetworkConnectivityListener.ConnectivityListener {

    private static final String TAG = MainActivity.class.getSimpleName();

    @Bind(R.id.coordinatorLayout)
    public CoordinatorLayout mCoordinatorLayout;

    @Bind(R.id.toolbar)
    public Toolbar mToolbar;

    @Bind(R.id.drawer_layout)
    public DrawerLayout mDrawerLayout;

    @Bind(R.id.nav_view)
    public NavigationView mNavigationView;

    protected ServiceProvider             mServiceProvider;
    protected boolean                     mDrawerBackOpen;
    protected NetworkConnectivityListener mNetworkConnectivityListener;
    protected List<Website>               mWebsites;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (isFabricEnable()) {
            Fabric.with(this, new Crashlytics());
        }
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        setSupportActionBar(mToolbar);

        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, mDrawerLayout, mToolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        mDrawerLayout.setDrawerListener(toggle);
        toggle.syncState();

        mNavigationView.setNavigationItemSelectedListener(this);

        setupServices();
        populateNavigationView();

        mNetworkConnectivityListener = new NetworkConnectivityListener();
        mNetworkConnectivityListener.startListening(this, this);

        if(SpStorage.isFirstLaunch(this)){
            changeFragment(Fragment.instantiate(this, WebsiteChooserFragment.class.getName()), false, false);
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        BusProvider.getInstance().register(this);
    }

    @Override
    public void onPause() {
        super.onPause();

        BusProvider.getInstance().unregister(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mServiceProvider.unregister(BusProvider.getInstance());
        mNetworkConnectivityListener.stopListening();
    }

    @Override
    public void onBackPressed() {
        if (mDrawerLayout.isDrawerOpen(GravityCompat.START) && !mDrawerBackOpen) {
            mDrawerLayout.closeDrawer(GravityCompat.START);
        } else {
            int fragmentCount = getSupportFragmentManager().getBackStackEntryCount();
            if (fragmentCount == 1) {
                if (!mDrawerBackOpen) {
                    mDrawerBackOpen = true;
                    mDrawerLayout.openDrawer(GravityCompat.START);
                    return;
                } else {
                    mDrawerBackOpen = false;
                    finish();
                    return;
                }
            }
            super.onBackPressed();
        }
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_restore:
                changeFragment(
                        WebsiteChooserFragment.newInstance(WebsiteChooserFragment.BUNDLE_MODE_RESTORE),
                        true,
                        true);
                return true;
            case R.id.action_about:
                changeFragment(
                        Fragment.instantiate(this, AboutFragment.class.getName()),
                        true,
                        true);
                return true;
            case R.id.action_settings:
                changeFragment(
                        Fragment.instantiate(this, SettingsFragment.class.getName()),
                        true,
                        true);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        switch (item.getGroupId()) {
            case R.id.drawer_group_content:
                for(Website website : mWebsites){
                    if(website.name.equals(item.getTitle())){
                        changeAnecdoteFragment(website);
                        break;
                    }
                }
                break;
            case R.id.drawer_group_action:
                changeFragment(
                        WebsiteChooserFragment.newInstance(WebsiteChooserFragment.BUNDLE_MODE_ADD),
                        true,
                        true);
                break;
            default:
                Toast.makeText(this, "NavigationGroup not managed", Toast.LENGTH_SHORT).show();
                break;
        }

        mDrawerLayout.closeDrawer(GravityCompat.START);
        return true;
    }

    /***************************
     * inner methods
     ***************************/

    /**
     * Change tu current displayed fragment by a new one.
     *
     * @param frag            the new fragment to display
     * @param saveInBackstack if we want the fragment to be in backstack
     * @param animate         if we want a nice animation or not
     */
    private void changeFragment(Fragment frag, boolean saveInBackstack, boolean animate) {
        String backStateName = ((Object) frag).getClass().getName();

        if(frag instanceof AnecdoteFragment){
            backStateName += frag.getArguments().getInt(AnecdoteFragment.ARGS_WEBSITE_ID);
        }

        try {
            FragmentManager manager = getSupportFragmentManager();
            boolean fragmentPopped = manager.popBackStackImmediate(backStateName, 0);

            if (!fragmentPopped && manager.findFragmentByTag(backStateName) == null) { //fragment not in back stack, create it.
                FragmentTransaction transaction = manager.beginTransaction();

                if (animate) {
                    Log.d(TAG, "Change Fragment: animate");
                    transaction.setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right);
                }

                transaction.replace(R.id.fragment_container, frag, backStateName);

                if (saveInBackstack) {
                    Log.d(TAG, "Change Fragment: addToBackTack " + backStateName);
                    transaction.addToBackStack(backStateName);
                } else {
                    Log.d(TAG, "Change Fragment: NO addToBackTack " + backStateName);
                }

                transaction.commit();
            } else if(!fragmentPopped && manager.findFragmentByTag(backStateName) != null) {
                Log.d(TAG, "Fragment not popped but finded: " + backStateName);
            } else {
                Log.d(TAG, "Change Fragment: nothing to do : " + backStateName + " fragmentPopped: " + fragmentPopped);
                // custom effect if fragment is already instanciated
            }
        } catch (IllegalStateException exception) {
            Log.w(TAG, "Unable to commit fragment, could be activity as been killed in background. " + exception.toString());
        }
    }

    /**
     * Change the current fragment to a new one displaying given website spec
     *
     * @param website the website specification to be displayed in the fragment
     */
    private void changeAnecdoteFragment(Website website){

        Fragment fragment = Fragment.instantiate(this, AnecdoteFragment.class.getName());
        Bundle bundle = new Bundle();
        bundle.putInt(AnecdoteFragment.ARGS_WEBSITE_ID, website.id);
        fragment.setArguments(bundle);
        changeFragment(fragment, true, false);
    }

    private void setupServices() {
        mWebsites = SpStorage.getWebsites(this);
        if(mServiceProvider != null){
            mServiceProvider.unregister(BusProvider.getInstance());
        }
        mServiceProvider = new ServiceProvider(mWebsites);
        mServiceProvider.register(this, BusProvider.getInstance());

        if(!mWebsites.isEmpty()){
            changeAnecdoteFragment(mWebsites.get(0));
        }
    }

    private void populateNavigationView(){
        // Setup NavigationView
        Menu navigationViewMenu = mNavigationView.getMenu();
        navigationViewMenu.clear();

        for (final Website website : mWebsites) {
            final ImageButton imageButton = (ImageButton) navigationViewMenu
                    .add(R.id.drawer_group_content, Menu.NONE, Menu.NONE, website.name)
                    .setActionView(R.layout.navigationview_actionlayout)
                    .getActionView();

            imageButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    PopupMenu popup = new PopupMenu(MainActivity.this, imageButton);
                    //Inflating the Popup using xml file
                    popup.getMenuInflater()
                            .inflate(R.menu.website_popup, popup.getMenu());

                    //registering popup with OnMenuItemClickListener
                    popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                        public boolean onMenuItemClick(MenuItem item) {

                            switch (item.getItemId()){
                                case R.id.action_edit:
                                    openWebsiteDialog(website);
                                    break;
                                case R.id.action_delete:
                                    SpStorage.deleteWebsite(MainActivity.this, website);
                                    BusProvider.getInstance().post(new WebsitesChangeEvent());
                                    break;
                                case R.id.action_default:
                                    SpStorage.setDefaultWebsite(MainActivity.this, website);
                                    BusProvider.getInstance().post(new WebsitesChangeEvent());
                                    break;

                            }
                            return true;
                        }
                    });

                    popup.show();
                }
            });
        }

        navigationViewMenu.add(R.id.drawer_group_action, Menu.NONE, Menu.NONE, R.string.action_website_add)
                .setIcon(R.drawable.ic_action_content_add);

        navigationViewMenu.setGroupCheckable(R.id.drawer_group_content, true, true);
        navigationViewMenu.getItem(0).setChecked(true);
    }

    /**
     * Open a dialog to edit given website or create a new one. One save/add, will fire {@link WebsitesChangeEvent}
     *
     * @param website website to edit
     */
    private void openWebsiteDialog(@Nullable Website website){
        FragmentManager fm = getSupportFragmentManager();
        DialogFragment dialogFragment = WebsiteDialogFragment.newInstance(website);
        dialogFragment.show(fm, dialogFragment.getClass().getSimpleName());
    }

    /**
     * Get the anecdote Service corresponding to the given name
     *
     * @param websiteId the website id to get the service from
     * @return an anecdote Service, if one is find
     */
    @Nullable
    public AnecdoteService getAnecdoteService(int websiteId) {
        return mServiceProvider.getAnecdoteService(websiteId);
    }

    public WebsiteApiService getWebsiteApiService(){
        return mServiceProvider.getWebsiteApiService();
    }

    /**
     * Return true if fabric is enable, checking the BuildConfig
     *
     * @return true if enable, false otherweise
     */
    private static boolean isFabricEnable() {
        return !Configuration.DEBUG;
    }

    /***************************
     * Event
     ***************************/

    @Subscribe
    public void onRequestFailed(final RequestFailedEvent event) {
        Log.d(TAG, "requestFailed:  " + event.getClass().getCanonicalName());

        //noinspection WrongConstant
        Snackbar
                .make(mCoordinatorLayout, event.message, Snackbar.LENGTH_INDEFINITE)
                .setAction("Retry", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        BusProvider.getInstance().post(event.originalEvent);
                    }
                })
                .show();
    }

    @Subscribe
    public void changeTitle(ChangeTitleEvent event) {
        if(event.websiteId != null){
            for(int i = 0; i < mWebsites.size(); i++){
                if(mWebsites.get(i).id == event.websiteId){
                    mToolbar.setTitle(mWebsites.get(i).name);
                    mNavigationView.getMenu().getItem(i).setChecked(true);
                    break;
                }
            }
        } else {
            mToolbar.setTitle(event.title);
        }
    }

    @Subscribe
    public void onWebsitesChangeEvent(WebsitesChangeEvent event){
        if(event.fromWebsiteChooserOverride){
            Log.d(TAG, "onWebsitesChangeEvent: removing all fragments");
            // if we come after a restoration or at the first start

            // 1. remove all already added fragment
            FragmentManager fm = getSupportFragmentManager();
            for(int i = 0; i < fm.getBackStackEntryCount(); ++i) {
                fm.popBackStack();
            }
            // 2. remove the WebsiteChooserFragment
            Fragment fragment = fm.findFragmentByTag(WebsiteChooserFragment.class.getName());
            if(fragment != null){
                FragmentTransaction transaction = fm.beginTransaction();
                transaction.remove(fragment).commit();

            }
        }
        setupServices();
        populateNavigationView();
        BusProvider.getInstance().post(new UpdateAnecdoteFragmentEvent());
    }

    /***************************
     * Implements NetworkConnectivityListener.ConnectivityListener
     ***************************/

    @Override
    public void onConnectivityChange(NetworkConnectivityListener.State state) {
        Log.d(TAG, "onConnectivityChange: " + state);
        BusProvider.getInstance().post(new NetworkConnectivityChangeEvent(state));
    }
}