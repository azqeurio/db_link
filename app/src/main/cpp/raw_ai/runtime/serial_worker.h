#pragma once

#include <thread>
#include <mutex>
#include <condition_variable>
#include <queue>
#include <functional>
#include <atomic>

namespace raw_ai {

class SerialWorker {
public:
    SerialWorker();
    ~SerialWorker();

    // Starts the worker thread
    void start();

    // Stops the worker thread and joins it
    void stop();

    // Submits a task to the FIFO queue
    void post(std::function<void()> task);

    // Block calling thread until all posted tasks are executed
    void wait();

private:
    void run();

    std::thread thread_;
    std::mutex mutex_;
    std::condition_variable cv_;
    std::condition_variable cvWait_;

    std::queue<std::function<void()>> queue_;
    std::atomic<bool> running_;
    std::atomic<int> activeTasks_;
};

} // namespace raw_ai
