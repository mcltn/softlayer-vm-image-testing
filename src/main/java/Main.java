import com.softlayer.api.RestApiClient;
import com.softlayer.api.service.Account;
import com.softlayer.api.service.Location;
import com.softlayer.api.service.virtual.Guest;
import com.softlayer.api.service.container.virtual.guest.Configuration;
import com.softlayer.api.service.container.virtual.guest.configuration.Option;
import com.softlayer.api.service.container.disk.image.capture.Template;
import com.softlayer.api.service.software.component.Password;
import com.softlayer.api.service.virtual.guest.block.Device;
import com.softlayer.api.service.virtual.guest.block.device.template.Group;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class Main {

    public static void main(String[] args) throws Exception {

        String baseUrl = args.length == 3 ? args[2] : RestApiClient.BASE_URL;
        if (!baseUrl.endsWith("/")) {
            baseUrl += '/';
        }

        // *************************************
        // Setup parameters
        // *************************************
        String username = "";
        String apiKey = "";
        String hostname = "aa-vm-1";
        String hostname2 = hostname + "-img";
        String domain = "example.cc";
        String datacenter1 = "dal09";
        String datacenter2 = "sjc01";
        // *************************************
        // *************************************


        RestApiClient client = new RestApiClient(baseUrl).withCredentials(username, apiKey);

        // Build an instance
        Guest vm1 = buildServer(client, hostname, domain, datacenter1, null);
        System.out.format("Virtual server: %s\n", vm1.getHostname());

        // Wait for instance
        vm1 = waitForInstance(client, vm1);

        // Create an Image
        String templateName = vm1.getHostname() + "-img";
        buildImage(client, vm1, templateName);

        // Build an instance from Image
        Guest vm2 = buildServer(client, hostname2, domain, datacenter2, templateName);

        // Wait for instance
        vm2 = waitForInstance(client, vm2);

        // Keep everything up for 10 minutes
        TimeUnit.MINUTES.sleep(10);

        // Remove Devices
        System.out.format("Removing VM.\n\n");
        vm1.asService(client).deleteObject();
        vm2.asService(client).deleteObject();

        // Remove Image
        System.out.format("Removing Image.\n\n");
        deleteImage(client, templateName);

        System.out.format("Completed.\n\n");
    }

    private static Guest buildServer(RestApiClient client, String hostname, String domain, String datacenter, String imageName) {
        Guest.Service service = Guest.service(client);

        Configuration orderOptions = service.getCreateObjectOptions();

        com.softlayer.api.service.virtual.Guest guest = new com.softlayer.api.service.virtual.Guest();
        guest.setHostname(hostname);
        guest.setDomain(domain);
        guest.setStartCpus(2L);
        guest.setMaxMemory(2048l);
        guest.setHourlyBillingFlag(true);
        guest.setLocalDiskFlag(true);

        guest.setDatacenter(new Location());
        guest.getDatacenter().setName(datacenter);


        if (imageName == null || imageName == "") {
            guest.setOperatingSystemReferenceCode("UBUNTU_LATEST");

            // Block devices are indexed starting at 0, but 1 is reserved for swap usage. Options can be used
            //  here also to set the smallest disk size allowed for the disk at index 0.
            Device device = null;
            for (Option option : orderOptions.getBlockDevices()) {
                for (Device candidate : option.getTemplate().getBlockDevices()) {
                    if ("0".equals(candidate.getDevice()) && (device == null ||
                            device.getDiskImage().getCapacity() > candidate.getDiskImage().getCapacity())) {
                        device = candidate;
                    }
                }
            }
            guest.getBlockDevices().add(device);
        } else {
            Account.Service accountService = Account.service(client);
            for (Group blockGroup : accountService.getPrivateBlockDeviceTemplateGroups()){
                if (imageName.equals(blockGroup.getName()))
                    guest.setBlockDeviceTemplateGroup(blockGroup);
            }
        }


        System.out.println("Ordering virtual server");
        guest = service.createObject(guest);
        System.out.format("Order completed for virtual server with UUID: %s\n", guest.getGlobalIdentifier());

        return guest;

    }

    private static Guest waitForInstance(RestApiClient client, Guest vm) throws Exception {
        Guest.Service service = Guest.service(client);
        boolean building = true;
        while (building) {
            service = vm.asService(client);

            service.withMask().status().name();
            service.withMask().provisionDate();
            service.withMask().blockDevices();
            service.withMask().blockDevices().diskImage();
            service.withMask().blockDevices().guest();
            service.withMask().blockDevices().status();

            vm = service.getObject();

            System.out.format("Status: %s\n", vm.getStatus().getName());
            if ("Active".equals(vm.getStatus().getName()) && vm.getProvisionDate() != null) {
                building = false;
            }

            // Going to wait even if the server comes online to allow to stabilize before building image
            System.out.format("Waiting 2 minutes...\n\n");
            TimeUnit.MINUTES.sleep(2);

        }
        System.out.format("Done.\n");

        return vm;
    }

    private static void buildImage(RestApiClient client, Guest vm, String templateName) throws Exception {
        System.out.format("Building Image: %s\n\n", templateName);
        Guest.Service service = Guest.service(client);
        service = vm.asService(client);

        /* Flex Image
        Template template = new Template();
        template.setName(templateName);
        template.setDescription("Image of " + vm1.getHostname());
        service.captureImage(template);
        System.out.format("Created Image: %s\n", templateName);
        */

        // Standard Image
        List<Device> blockDevices = new ArrayList<Device>();
        for (Device device : vm.getBlockDevices()) {
            if (device.getBootableFlag() == 1) {
                blockDevices.add(device);
            }
        }
        service.createArchiveTransaction(templateName, blockDevices, "Image of " + vm.getHostname());

        // Going to wait for build of image
        System.out.format("Waiting 5 minutes...\n\n");
        TimeUnit.MINUTES.sleep(5);
    }

    private static void deleteImage(RestApiClient client, String imageName) {
        Account.Service accountService = Account.service(client);
        for (Group blockGroup : accountService.getPrivateBlockDeviceTemplateGroups()){
            if (imageName == blockGroup.getName()) {
                Group.Service grpService = blockGroup.asService(client);
                grpService.deleteObject();
            }
        }
    }
}
